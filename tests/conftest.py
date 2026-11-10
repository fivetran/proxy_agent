"""Shared pytest fixtures for isolated script installations and Windows hosting.

Sandboxes select Bash or PowerShell and the simulated container OS. The Windows
runner fixture owns each spawned Python.NET process, its timeout, and cleanup;
.NET imports stay in the child so pytest never loads either CLR in its process.
The private engine helper below supplies native SDK bindings and observations.
"""

from functools import lru_cache
import json
import multiprocessing
import os
from pathlib import Path
import subprocess
import sys

import pytest
from support.harness import REPO, WINDOWS, Sandbox


@pytest.fixture(scope='session')
def windows_engine_info():
    """Cache the selected executable's engine metadata without loading a CLR."""

    @lru_cache(maxsize=None)
    def lookup(shell):
        """Discover the engine and assembly from the actual executable under test."""
        env = {key: value for key, value in os.environ.items() if key.upper() != 'PSMODULEPATH'}
        result = subprocess.run(
            [
                shell,
                '-NoLogo',
                '-NoProfile',
                '-NonInteractive',
                '-Command',
                '[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false); '
                '[pscustomobject]@{home=$PSHOME; version=$PSVersionTable.PSVersion.ToString(); '
                'major=$PSVersionTable.PSVersion.Major; assembly=[System.Management.Automation.PowerShell].Assembly.Location} '
                '| ConvertTo-Json -Compress',
            ],
            env=env,
            capture_output=True,
            text=True,
            encoding='utf-8',
            check=True,
            timeout=10,
        )
        return json.loads(result.stdout.lstrip('\ufeff'))

    return lookup


@pytest.fixture
def windows_runner(windows_engine_info):
    """Run each hosted engine in a spawned child, always disposing of the process.

    This factory is injected into sandboxes. CLR/runtime/proxy state stays in the
    child, while the fixture owns timeout enforcement and failure propagation.
    """

    def run(request, *, shell, env, timeout=30):
        root = Path(env['AGENT_TEST_ROOT'])
        failure = root / 'stub-error.txt'
        result_path = root / 'host-result.json'
        failure.unlink(missing_ok=True)
        result_path.unlink(missing_ok=True)
        env = {
            key: value
            for key, value in env.items()
            if key.upper() != 'PSMODULEPATH' and not key.upper().startswith('PYTHONNET_')
        }
        env.update(
            AGENT_TEST_RUNNER=str(REPO / 'tests/support/stub.py'),
            PYTHONIOENCODING='utf-8',
            PSExecutionPolicyPreference='Bypass',
        )
        process = multiprocessing.get_context('spawn').Process(
            target=_hosted_script, args=(request, windows_engine_info(shell), env)
        )
        process.start()
        try:
            process.join(timeout)
            if process.is_alive():
                raise subprocess.TimeoutExpired(shell, timeout)
            if process.exitcode != 0 or not result_path.exists():
                raise RuntimeError(f'Python.NET host failed (exit {process.exitcode})')
            result = json.loads(result_path.read_text(encoding='utf-8'))
            code, transcript = result['code'], result['output']
            if failure.exists():
                code = 1
                transcript += '\n' + failure.read_text(encoding='utf-8')
            return subprocess.CompletedProcess([shell, request['script']], code, transcript)
        finally:
            if process.is_alive():
                process.kill()
            process.join()
            process.close()

    return run


@pytest.fixture
def sandbox(request, windows_runner):
    """Create one installation; an indirect parameter selects its container OS."""
    container_os = getattr(request, 'param', 'linux')
    if container_os == 'windows' and not WINDOWS:
        pytest.skip('Windows containers require the PowerShell scripts')
    installation = Sandbox(container_os, windows_runner=windows_runner)
    try:
        yield installation
    finally:
        installation.close()


def _hosted_script(request, info, env):
    """Spawn target: load one CLR and dispose its runspace before process exit.

    Keep this importable at module scope for multiprocessing on Windows. The
    parent fixture owns the process; this function owns only native resources.
    """
    os.environ.clear()
    os.environ.update(env)
    engine = _PowerShellEngine(info)
    try:
        engine.setup(request)
        code, output = engine.execute(request)
        observed = engine.snapshot(request)
        Path(request['snapshot_path']).write_text(json.dumps(observed), encoding='utf-8')
        (Path(env['AGENT_TEST_ROOT']) / 'host-result.json').write_text(
            json.dumps(dict(code=code, output=output)), encoding='utf-8'
        )
    finally:
        engine.space.Dispose()


class _PowerShellEngine:
    """Expose the public PowerShell SDK while keeping .NET imports inside the child."""

    def __init__(self, info):
        from pythonnet import load

        home = Path(info['home'])
        if info['major'] == 5:
            load('netfx')
        elif info['major'] == 7:
            load('coreclr', entry_dll=str(home / 'pwsh.dll'), dotnet_root=str(home))
        else:
            raise ValueError(f'Unsupported PowerShell version: {info["version"]}')
        import clr

        sys.path.insert(0, str(home))
        clr.AddReference(info['assembly'])
        import System
        import System.Management.Automation as automation
        from System.Management.Automation.Runspaces import RunspaceFactory

        self.system = System
        self.automation = automation
        self.space = RunspaceFactory.CreateRunspace()
        self.space.Open()
        actual_version = str(self.evaluate('$PSVersionTable.PSVersion.ToString()')[0])
        if actual_version != info['version']:
            self.space.Dispose()
            raise RuntimeError(f'Loaded PowerShell {actual_version}, expected {info["version"]}')
        self.http_client = None
        if info['major'] == 7:
            clr.AddReference('System.Net.Http')
            from System.Net.Http import HttpClient

            self.http_client = HttpClient

    def managed(self, value):
        """Bind arrays, dictionaries and booleans as .NET values, never source text."""
        if isinstance(value, dict):
            from System.Collections import Hashtable

            result = Hashtable()
            for key, item in value.items():
                result.Add(key, self.managed(item))
            return result
        if isinstance(value, (list, tuple)):
            return self.system.Array[self.system.Object]([self.managed(item) for item in value])
        if isinstance(value, bool):
            return self.system.Boolean(value)
        if isinstance(value, int):
            return self.system.Int64(value)
        return value

    def pipeline(self, source, *arguments):
        pipeline = self.automation.PowerShell.Create()
        pipeline.Runspace = self.space
        pipeline.AddScript(source, False)
        for value in arguments:
            pipeline.AddArgument(self.managed(value))
        return pipeline

    def evaluate(self, source, *arguments):
        """Evaluate a fixed native binding expression with separately bound arguments."""
        pipeline = self.pipeline(source, *arguments)
        try:
            return [item.BaseObject for item in pipeline.Invoke()]
        finally:
            pipeline.Dispose()

    def setup(self, request):
        """Shadow external commands and install a controlled native system proxy."""
        variables = self.space.SessionStateProxy
        variables.SetVariable('ErrorActionPreference', self.automation.ActionPreference.Stop)
        variables.SetVariable('LASTEXITCODE', self.system.Int32(0))
        variables.SetVariable('ManagerExitCode', self.system.Int32(0))
        variables.SetVariable('OutputEncoding', self.system.Text.UTF8Encoding(False))
        functions = {
            'docker': 'ConvertTo-Json -InputObject @($args) -Compress | '
            '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB docker-json; '
            '$global:LASTEXITCODE = $LASTEXITCODE',
            'Invoke-TestStub': 'param([string]$Name, [object[]]$Arguments) '
            'ConvertTo-Json -InputObject (@($Name) + @($Arguments)) -Depth 10 -Compress | '
            '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_RUNNER native | Out-String | Invoke-Expression',
        }
        commands = ['Get-CimInstance', 'Get-PSDrive']
        if request['stub_http']:
            commands += ['Invoke-RestMethod', 'Invoke-WebRequest']
        for name in commands:
            functions[name] = f'Invoke-TestStub -Name {name} -Arguments $args'
        for name, body in functions.items():
            self.evaluate(
                'param($name,$body) Set-Item -LiteralPath ("Function:global:" + $name) '
                '-Value ([scriptblock]::Create($body))',
                name,
                body,
            )
        from System.Net import NetworkCredential, WebProxy, WebRequest

        self.proxy = WebProxy(request['system_proxy']) if request['system_proxy'] else WebProxy()
        if request['system_credentials']:
            self.proxy.Credentials = NetworkCredential('proxy-user', 'proxy-password')
        self.credentials = self.proxy.Credentials
        WebRequest.DefaultWebProxy = self.proxy
        if self.http_client is not None:
            self.http_client.DefaultProxy = self.proxy

    def snapshot(self, request):
        """Read native objects directly before the child and its runspace are disposed."""
        result = {}
        if request['observe_proxy']:
            # The SDK unwraps the concrete object; the static .NET property is
            # exposed by pythonnet as IWebProxy, which hides WebProxy fields.
            proxy = self.evaluate('[Net.WebRequest]::DefaultWebProxy')[0]
            credentials = proxy.Credentials
            network = (
                credentials.GetCredential(self.system.Uri('http://proxy-test.invalid'), 'Basic')
                if credentials
                else None
            )
            result = dict(
                proxy_address=str(proxy.Address) if proxy.Address else '',
                proxy_user=str(network.UserName) if network else None,
                proxy_password=str(network.Password) if network else None,
                default_credentials=bool(proxy.UseDefaultCredentials),
                same_system_proxy=bool(self.system.Object.ReferenceEquals(self.proxy, proxy)),
                same_credentials=bool(self.system.Object.ReferenceEquals(self.credentials, credentials)),
                bypass={uri: bool(proxy.IsBypassed(self.system.Uri(uri))) for uri in request['proxy_probes']},
            )
            if self.http_client is not None:
                result['http_client_proxy'] = bool(
                    self.system.Object.ReferenceEquals(proxy, self.http_client.DefaultProxy)
                )
        if request['config_path'] and Path(request['config_path']).exists():
            import clr
            from System.Security.Principal import (
                SecurityIdentifier,
                WindowsBuiltInRole,
                WindowsIdentity,
                WindowsPrincipal,
            )

            acl = self.evaluate('param($path) Get-Acl -LiteralPath $path', request['config_path'])[0]
            identity = WindowsIdentity.GetCurrent()
            try:
                result.update(
                    administrator=bool(WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator)),
                    user_sid=str(identity.User.Value),
                    acl_protected=bool(acl.AreAccessRulesProtected),
                    acl=[
                        dict(
                            sid=str(rule.IdentityReference.Value),
                            rights=str(rule.FileSystemRights),
                            type=str(rule.AccessControlType),
                        )
                        for rule in acl.GetAccessRules(True, True, clr.GetClrType(SecurityIdentifier))
                    ],
                )
            finally:
                identity.Dispose()
        return result

    def execute(self, request):
        """Keep partial output and script exit codes even when invocation throws."""
        pipeline = self.pipeline(
            'param($script,$arguments,$parameters) & $script @arguments @parameters; '
            '$global:ManagerExitCode = $LASTEXITCODE',
            request['script'],
            request['arguments'],
            request['parameters'],
        )
        output = self.automation.PSDataCollection[self.automation.PSObject]()
        failure = None
        try:
            try:
                pipeline.Invoke[self.automation.PSObject, self.automation.PSObject](None, output, None)
            except self.automation.RuntimeException as error:
                failure = str(error.Message)
            transcript = [str(item) for item in output]
            transcript += [str(item) for item in pipeline.Streams.Error]
            transcript += [str(item.Message) for item in pipeline.Streams.Warning]
            transcript += [str(item.Message) for item in pipeline.Streams.Verbose]
            transcript += [
                str(item.MessageData.Message) if 'PSHOST' in list(item.Tags) else str(item.MessageData)
                for item in pipeline.Streams.Information
            ]
            if failure is not None:
                transcript.append(failure)
            code = (
                1 if failure is not None else int(self.space.SessionStateProxy.GetVariable('ManagerExitCode'))
            )
            return code, '\n'.join(transcript) + '\n'
        finally:
            pipeline.Dispose()
            output.Dispose()
