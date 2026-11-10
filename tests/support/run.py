"""Prepare and supervise the disposable PowerShell host from Python.

Python selects mocks, builds arguments, validates helper responses, and tracks
infrastructure failures independently of exceptions caught by the tested script.
Generated PowerShell only binds native commands, invokes the script, and reads
objects that exist inside that process. It is saved in the sandbox for inspection.
"""

import json
import os
from functools import lru_cache
from pathlib import Path
import subprocess
import sys


def literal(value):
    """Bind scalar values without interpolation, preserving switch booleans."""
    if value is None:
        return '$null'
    if isinstance(value, bool):
        return '$true' if value else '$false'
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def array(values):
    return '@(' + ', '.join(map(literal, values)) + ')'


@lru_cache(maxsize=None)
def shell_major(shell):
    """Query once per host; Python selects APIs supported by that PowerShell version."""
    result = subprocess.run(
        [shell, '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', '$PSVersionTable.PSVersion.Major'],
        capture_output=True,
        text=True,
        check=True,
        timeout=10,
    )
    return int(result.stdout.strip())


def program(request, major):
    """Build one host script; all fixture-dependent choices happen here."""
    runner = literal(Path(__file__).resolve())
    lines = [
        "$ErrorActionPreference = 'Stop'",
        '$global:LASTEXITCODE = 0',
        '$OutputEncoding = [System.Text.UTF8Encoding]::new($false)',
        '[Console]::OutputEncoding = $OutputEncoding',
        'function docker {',
        '    ConvertTo-Json -InputObject @($args) -Compress | '
        '& $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB docker-json',
        '    $global:LASTEXITCODE = $LASTEXITCODE',
        '}',
    ]
    commands = ['Get-CimInstance', 'Get-PSDrive']
    if request['stub_http']:
        commands += ['Invoke-RestMethod', 'Invoke-WebRequest']
    for name in commands:
        lines += [
            f'function {name} {{',
            f'    ConvertTo-Json -InputObject (@({literal(name)}) + @($args)) -Depth 10 -Compress | '
            f'& $env:AGENT_TEST_PYTHON {runner} stub | Out-String | Invoke-Expression',
            '}',
        ]
    proxy = literal(request['system_proxy']) if request['system_proxy'] else ''
    lines.append(f'$systemProxy = [System.Net.WebProxy]::new({proxy})')
    if request['system_credentials']:
        lines.append(
            "$systemProxy.Credentials = [System.Net.NetworkCredential]::new('proxy-user', 'proxy-password')"
        )
    lines += [
        '$originalCredentials = $systemProxy.Credentials',
        '[System.Net.WebRequest]::DefaultWebProxy = $systemProxy',
    ]
    if major >= 7:
        lines.append('[System.Net.Http.HttpClient]::DefaultProxy = $systemProxy')
    parameters = '; '.join(
        f'{literal(key)} = {literal(value)}' for key, value in request['parameters'].items()
    )
    lines += [
        f'$parameters = @{{{parameters}}}',
        f'$arguments = {array(request["arguments"])}',
        # PowerShell must catch its own terminating errors before observations
        # can be read. Python checks the process status and helper failures.
        'try {',
        f'    & {literal(request["script"])} @arguments @parameters',
        '    $code = $LASTEXITCODE',
        '} catch { Write-Output "$_"; $code = 1 }',
        '$snapshot = @{}',
    ]
    if request['observe_proxy']:
        bypass = '; '.join(
            f'{literal(uri)} = $proxy.IsBypassed([Uri]{literal(uri)})' for uri in request['proxy_probes']
        )
        lines += [
            '$proxy = [System.Net.WebRequest]::DefaultWebProxy',
            '$snapshot = @{',
            '    proxy_address = [string]$proxy.Address',
            '    proxy_user = $proxy.Credentials.UserName',
            '    proxy_password = $proxy.Credentials.Password',
            '    default_credentials = $proxy.UseDefaultCredentials',
            '    same_system_proxy = [object]::ReferenceEquals($systemProxy, $proxy)',
            '    same_credentials = [object]::ReferenceEquals($originalCredentials, $proxy.Credentials)',
            f'    bypass = @{{{bypass}}}',
            '}',
        ]
        if major >= 7:
            lines.append(
                '$snapshot.http_client_proxy = [object]::ReferenceEquals($proxy, [System.Net.Http.HttpClient]::DefaultProxy)'
            )
    if request['config_path']:
        lines += [
            f'$acl = Get-Acl -LiteralPath {literal(request["config_path"])} -ErrorAction SilentlyContinue',
            '$snapshot.administrator = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)',
            '$snapshot.acl_protected = $acl.AreAccessRulesProtected',
            '$snapshot.acl = @($acl.Access | Select-Object '
            "@{Name='sid'; Expression={$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value}}, "
            "@{Name='rights'; Expression={[string]$_.FileSystemRights}}, "
            "@{Name='type'; Expression={[string]$_.AccessControlType}})",
            '$snapshot.user_sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value',
        ]
    lines += [
        f'$snapshot | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath {literal(request["snapshot_path"])} -Encoding UTF8',
        'exit $code',
    ]
    return '\n'.join(lines) + '\n'


def invoke(request, *, shell, env, timeout=30):
    """Run the host and fail closed when a mocked cmdlet's Python helper breaks."""
    root = Path(env['AGENT_TEST_ROOT'])
    failure = root / 'stub-error.txt'
    failure.unlink(missing_ok=True)
    script = root / 'powershell-host.ps1'
    script.write_text(program(request, shell_major(shell)), encoding='utf-8-sig')
    env = {key: value for key, value in env.items() if key.upper() != 'PSMODULEPATH'}
    env['PYTHONIOENCODING'] = 'utf-8'
    result = subprocess.run(
        [
            shell,
            '-NoLogo',
            '-NoProfile',
            '-NonInteractive',
            '-ExecutionPolicy',
            'Bypass',
            '-File',
            str(script),
        ],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=timeout,
    )
    if failure.exists():
        result.returncode = 1
        result.stdout += '\n' + failure.read_text(encoding='utf-8')
    return result


def stub():
    """Validate one mocked cmdlet response and emit only safely quoted native binding.

    The helper protocol remains UTF-8 JSON. Infrastructure errors also persist
    outside PowerShell, so catching the emitted exception cannot hide a failure.
    """
    payload = sys.stdin.buffer.read()
    name = 'unknown'
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
        value = json.loads(response)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        message = str(error)
        (Path(os.environ['AGENT_TEST_ROOT']) / 'stub-error.txt').write_text(message, encoding='utf-8')
        print(f'throw {literal(message)}')
        return
    print(f'ConvertFrom-Json -InputObject {literal(json.dumps(value))}')


if __name__ == '__main__':
    if sys.argv[1:] != ['stub']:
        raise SystemExit('Usage: run.py stub (host orchestration is imported by harness.py)')
    stub()
