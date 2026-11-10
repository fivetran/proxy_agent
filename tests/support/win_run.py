"""Host the selected PowerShell engine through pythonnet in a disposable Python child.

Python owns setup, argument binding, observations, and failure
tracking. Each child loads either .NET Framework (PowerShell 5.1) or PowerShell
7's bundled CoreCLR, so runtime and proxy state cannot leak between tests.
See pythonnet-review.md for the pinned dependency and hosting assumptions.
"""

import base64
from functools import lru_cache
import json
import os
from pathlib import Path
import subprocess
import sys


@lru_cache(maxsize=None)
def shell_info(shell):
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


class Engine:
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
            '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_RUNNER stub | Out-String | Invoke-Expression',
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


def invoke(request, *, shell, env, timeout=30):
    """Run one hosted engine in a child; subprocess timeout also kills that CLR."""
    root = Path(env['AGENT_TEST_ROOT'])
    failure = root / 'stub-error.txt'
    result_path = root / 'host-result.json'
    failure.unlink(missing_ok=True)
    result_path.unlink(missing_ok=True)
    request_path = root / 'host-request.json'
    request_path.write_text(json.dumps(dict(request=request, engine=shell_info(shell))), encoding='utf-8')
    env = {
        key: value
        for key, value in env.items()
        if key.upper() != 'PSMODULEPATH' and not key.upper().startswith('PYTHONNET_')
    }
    env.update(
        AGENT_TEST_RUNNER=str(Path(__file__).resolve()),
        PYTHONIOENCODING='utf-8',
        PSExecutionPolicyPreference='Bypass',
    )
    command = [sys.executable, str(Path(__file__).resolve()), 'host', str(request_path)]
    process = subprocess.run(
        command,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=timeout,
    )
    if process.returncode != 0 or not result_path.exists():
        raise RuntimeError(f'Python.NET host failed (exit {process.returncode}): {process.stdout}')
    result = json.loads(result_path.read_text(encoding='utf-8'))
    code, transcript = result['code'], result['output'] + process.stdout
    if failure.exists():
        code = 1
        transcript += '\n' + failure.read_text(encoding='utf-8')
    return subprocess.CompletedProcess([shell, request['script']], code, transcript)


def host(path):
    """Child entry point; each process loads one CLR and disposes its runspace."""
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    engine = Engine(data['engine'])
    try:
        engine.setup(data['request'])
        code, output = engine.execute(data['request'])
        observed = engine.snapshot(data['request'])
        Path(data['request']['snapshot_path']).write_text(json.dumps(observed), encoding='utf-8')
        (Path(os.environ['AGENT_TEST_ROOT']) / 'host-result.json').write_text(
            json.dumps(dict(code=code, output=output)), encoding='utf-8'
        )
    finally:
        engine.space.Dispose()


def stub():
    """Validate mock JSON and bind its value; helper failures persist outside the shell.

    This callback runs during a pipeline, when a second pipeline cannot run
    in that runspace. Base64 carries response data in a fixed native expression without
    interpolating it into PowerShell source or implementing a quoting algorithm.
    """
    payload = sys.stdin.buffer.read()
    name = 'unknown'
    action = 'ConvertFrom-Json -InputObject'
    try:
        name = json.loads(payload.decode('utf-8-sig'))[0]
        result = subprocess.run(
            [os.environ['AGENT_TEST_PYTHON'], os.environ['AGENT_TEST_STUB'], 'powershell-json'],
            input=payload,
            capture_output=True,
            timeout=10,
        )
        response = result.stdout.decode('utf-8-sig')
        if result.returncode:
            raise ValueError(f'Test stub {name} failed (exit {result.returncode}): {response}')
        if not response:
            raise ValueError(f'Test stub {name} returned no JSON.')
        value = json.dumps(json.loads(response))
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        value = str(error)
        (Path(os.environ['AGENT_TEST_ROOT']) / 'stub-error.txt').write_text(value, encoding='utf-8')
        action = 'throw'
    encoded = base64.b64encode(value.encode('utf-8')).decode('ascii')
    print(f"{action} ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{encoded}')))")


if __name__ == '__main__':
    if sys.argv[1:] == ['stub']:
        stub()
    elif len(sys.argv) == 3 and sys.argv[1] == 'host':
        host(sys.argv[2])
    else:
        raise SystemExit('Usage: win_run.py stub | host REQUEST_JSON')
