"""Drive one isolated PowerShell process through pypsrp's local transport.

Python selects mocks, binds structured arguments, collects output, and decides
failure status. Small PowerShell expressions access objects inside the tested
process. See pypsrp-review.md for the exact dependency and transport review.
"""

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

import psrp
from psrp._connection.process import SyncProcessConnection
from psrpcore.types import RunspacePoolState


class LocalConnection(SyncProcessConnection):
    """Wake pending pipelines when the host dies before sending protocol completion."""

    def read(self):
        data = super().read()
        if not data and self.get_runspace_pool().state != RunspacePoolState.Closed:
            # The pinned transport clears acknowledgements on EOF but does not
            # fail pending pipeline waits. A killed host must break those too.
            self._break_runspace(RuntimeError('PowerShell host closed unexpectedly'))
        return data


class LocalProcess(psrp.ProcessInfo):
    """Adapt the reviewed transport to accept an environment and enforce a timeout.

    pypsrp 1.0.0b1's ProcessInfo omits both options. Its connection already owns
    protocol handling, so only process creation changes here. The private
    SyncProcessConnection import is deliberate and covered by the version pin.
    """

    def __init__(self, shell, env, timeout):
        arguments = ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-ServerMode']
        if Path(shell).stem.lower() == 'powershell':
            arguments[:0] = ['-Version', '5.1']
        super().__init__(executable=shell, arguments=arguments)
        self.env = {key: value for key, value in env.items() if key.upper() != 'PSMODULEPATH'}
        self.env['AGENT_TEST_RUNNER'] = str(Path(__file__).resolve())
        self.env['PYTHONIOENCODING'] = 'utf-8'
        self.process = None
        self.timed_out = False
        self.timer = threading.Timer(timeout, self.expire)
        self.timer.daemon = True

    def create_sync(self, pool, callback):
        self.process = subprocess.Popen(
            [self.executable, *self.arguments],
            env=self.env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
        )
        self.timer.start()
        return LocalConnection(pool, callback, self.process)

    def expire(self):
        self.timed_out = True
        if self.process is not None and self.process.poll() is None:
            self.process.kill()

    def cleanup(self):
        self.timer.cancel()
        if self.process is not None:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=5)
            self.process.stdin.close()
            self.process.stdout.close()


def evaluate(pool, source, *arguments):
    """Evaluate a fixed native expression with data serialized separately by PSRP."""
    pipeline = psrp.SyncPowerShell(pool).add_script(source, use_local_scope=False)
    for value in arguments:
        pipeline.add_argument(value)
    return pipeline.invoke()


def setup(pool, request):
    """Install selected mocks and a controlled system proxy in the same runspace."""
    evaluate(
        pool,
        "$global:ErrorActionPreference = 'Stop'; $global:LASTEXITCODE = 0; "
        '$global:ManagerExitCode = 0; $global:OutputEncoding = [Text.UTF8Encoding]::new($false)',
    )
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
        evaluate(
            pool,
            'param($name,$body) Set-Item -LiteralPath ("Function:global:" + $name) '
            '-Value ([scriptblock]::Create($body))',
            name,
            body,
        )
    if request['system_proxy']:
        evaluate(
            pool,
            'param($address) $global:systemProxy = [Net.WebProxy]::new($address)',
            request['system_proxy'],
        )
    else:
        evaluate(pool, '$global:systemProxy = [Net.WebProxy]::new()')
    if request['system_credentials']:
        evaluate(
            pool, "$systemProxy.Credentials = [Net.NetworkCredential]::new('proxy-user', 'proxy-password')"
        )
    evaluate(
        pool,
        '$global:originalCredentials = $systemProxy.Credentials; '
        '[Net.WebRequest]::DefaultWebProxy = $systemProxy',
    )
    major = int(evaluate(pool, '$PSVersionTable.PSVersion.Major')[0])
    if major >= 7:
        evaluate(pool, '[Net.Http.HttpClient]::DefaultProxy = $systemProxy')
    return major


def snapshot(pool, request, major):
    """Read native proxy/ACL values before disposing of the tested process."""
    result = {}
    if request['observe_proxy']:
        result.update(
            evaluate(
                pool,
                """
            $proxy = [Net.WebRequest]::DefaultWebProxy
            @{
                proxy_address = [string]$proxy.Address
                proxy_user = $proxy.Credentials.UserName
                proxy_password = $proxy.Credentials.Password
                default_credentials = $proxy.UseDefaultCredentials
                same_system_proxy = [object]::ReferenceEquals($systemProxy, $proxy)
                same_credentials = [object]::ReferenceEquals($originalCredentials, $proxy.Credentials)
            }
        """,
            )[0]
        )
        result['bypass'] = {
            uri: evaluate(pool, 'param($uri) [Net.WebRequest]::DefaultWebProxy.IsBypassed([Uri]$uri)', uri)[0]
            for uri in request['proxy_probes']
        }
        if major >= 7:
            result['http_client_proxy'] = evaluate(
                pool,
                '[object]::ReferenceEquals([Net.WebRequest]::DefaultWebProxy, [Net.Http.HttpClient]::DefaultProxy)',
            )[0]
    if request['config_path'] and Path(request['config_path']).exists():
        acl_json = evaluate(
            pool,
            """
            param($path)
            $acl = Get-Acl -LiteralPath $path
            @{
                administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
                acl_protected = $acl.AreAccessRulesProtected
                acl = @($acl.Access | ForEach-Object {
                    @{ sid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
                       rights = [string]$_.FileSystemRights; type = [string]$_.AccessControlType }
                })
                user_sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            } | ConvertTo-Json -Depth 6 -Compress
        """,
            request['config_path'],
        )[0]
        result.update(json.loads(acl_json))
    return result


def invoke(request, *, shell, env, timeout=30):
    """Preserve script exit status and output, with independent mock-failure tracking."""
    failure = Path(env['AGENT_TEST_ROOT']) / 'stub-error.txt'
    failure.unlink(missing_ok=True)
    connection = LocalProcess(shell, env, timeout)
    transcript = []
    code = 0
    try:
        with psrp.SyncRunspacePool(connection) as pool:
            major = setup(pool, request)
            pipeline = psrp.SyncPowerShell(pool).add_script(
                'param($script,$arguments,$parameters) & $script @arguments @parameters; '
                '$global:ManagerExitCode = $LASTEXITCODE',
                use_local_scope=False,
            )
            for value in (request['script'], request['arguments'], request['parameters']):
                pipeline.add_argument(value)
            output = psrp.SyncPSDataCollection()
            output.data_added += lambda value: transcript.append(str(value))
            pipeline.streams.error.data_added += lambda value: transcript.append(str(value))
            pipeline.streams.warning.data_added += lambda value: transcript.append(str(value.Message))
            pipeline.streams.verbose.data_added += lambda value: transcript.append(str(value.Message))
            pipeline.streams.information.data_added += lambda value: transcript.append(
                str(value.MessageData.Message) if 'PSHOST' in value.Tags else str(value.MessageData)
            )
            try:
                pipeline.invoke(output_stream=output)
                code = int(evaluate(pool, '$global:ManagerExitCode')[0])
            except psrp.PipelineFailed as error:
                transcript.append(str(error))
                code = 1
            observed = snapshot(pool, request, major)
            Path(request['snapshot_path']).write_text(json.dumps(observed), encoding='utf-8')
    except Exception:
        if connection.timed_out:
            raise subprocess.TimeoutExpired(shell, timeout) from None
        raise
    finally:
        connection.cleanup()
    if failure.exists():
        code = 1
        transcript.append(failure.read_text(encoding='utf-8'))
    return subprocess.CompletedProcess([shell, request['script']], code, '\n'.join(transcript) + '\n')


def stub():
    """Validate mock JSON and bind its value; helper failures persist outside the shell.

    This callback runs during a pipeline, when a second PSRP pipeline cannot be
    started. Base64 carries response data in a fixed native expression without
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
    if sys.argv[1:] != ['stub']:
        raise SystemExit('Usage: run.py stub (host orchestration is imported by harness.py)')
    stub()
