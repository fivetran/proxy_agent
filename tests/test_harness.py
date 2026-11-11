"""Check the test infrastructure that the script suites rely on.

Direct Python protocol checks run on every host. Windows checks replace the
copied manager with small PowerShell probes to isolate argument transport,
mock failures, and state observation from production behavior. These safeguards
prevent a broken bridge from making the script tests pass incorrectly.
"""

import json
import subprocess
import sys

import pytest
from support.harness import REPO, WINDOWS


@pytest.fixture
def probe(sandbox):
    """Replace the copied manager with a small script to isolate bridge behavior."""

    def run(source, *arguments):
        sandbox.manager.write_text(source, encoding='utf-8-sig')
        return sandbox.run(*arguments)

    return run


class TestStubProtocol:
    """Ensure invalid cmdlet inputs fail in Python without consulting the real host."""

    @pytest.mark.parametrize(
        'arguments,message',
        [
            (['Get-CimInstance', 'Win32_Processor'], 'Unexpected CIM class'),
            (['Invoke-RestMethod', '-Uri'], 'Missing value'),
            (['Invoke-RestMethod', '-Unexpected', 'value'], 'Unexpected Invoke-RestMethod parameter'),
        ],
        ids=['unknown-host', 'missing-value', 'unknown-parameter'],
    )
    def test_unsupported_host_queries_and_malformed_arguments_fail_closed(self, sandbox, arguments, message):
        (sandbox.root / 'options.json').write_text(json.dumps(sandbox.options))
        result = subprocess.run(
            [sys.executable, str(REPO / 'tests/support/stub.py'), 'powershell-json'],
            input=json.dumps(arguments).encode(),
            capture_output=True,
            env=sandbox.env,
            timeout=5,
        )
        assert result.returncode == 1, result.stderr
        assert message in json.loads(result.stdout)['error']
        assert not sandbox.calls('docker')

    @pytest.mark.parametrize(
        'source,message',
        [
            ('raise SystemExit(7)', 'failed (exit 7)'),
            ('pass', 'returned no JSON'),
            ("print('invalid JSON')", 'Expecting value'),
        ],
        ids=['failed-helper', 'empty-response', 'invalid-json'],
    )
    def test_python_bridge_persists_failures_outside_the_shell(self, sandbox, source, message):
        """A script catching the emitted exception cannot erase a helper failure."""
        helper = sandbox.root / "broken helper's response.py"
        helper.write_text(source)
        sandbox.env['AGENT_TEST_STUB'] = str(helper)

        result = subprocess.run(
            [sys.executable, str(REPO / 'tests/support/stub.py'), 'native'],
            input=json.dumps(['Invoke-RestMethod', '-Uri', 'https://unused.example.com']).encode(),
            capture_output=True,
            env=sandbox.env,
            timeout=5,
        )

        assert result.returncode == 0, result.stderr
        assert result.stdout.startswith(b'throw ')
        assert message in (sandbox.root / 'stub-error.txt').read_text(encoding='utf-8')


@pytest.mark.skipif(not WINDOWS, reason='requires the PowerShell child process')
class TestPowerShellBridge:
    """Verify the actual PowerShell-to-Python boundary under the selected shell.

    Probes exercise UTF-8 JSON forwarding, errors caught by the called script,
    and snapshots after exceptions. They require Windows, but no Docker daemon.
    """

    def test_native_arguments_preserve_quotes_empty_strings_spaces_and_unicode(self, sandbox, probe):
        arguments = [
            '',
            'two words',
            'a"b',
            "O'Brien",
            '雪',
            '--region',
            '‘’‚‛',
            '"“”„',
            '$env:TOKEN; $(throw "expanded"); `n',
            'line one\r\nline two\n',
        ]
        result = probe('docker logs @args', *arguments)
        assert result.returncode == 0, result.stdout
        assert sandbox.calls('docker') == [['logs', *arguments]]
        assert sandbox.snapshot == {}

    @pytest.mark.parametrize('code', [0, 3])
    def test_script_exit_status_preserves_native_observations(self, sandbox, probe, code):
        """SDK pipeline completion must preserve the nested script's exit status."""
        sandbox.observe_proxy = True
        result = probe(f'exit {code}')
        assert result.returncode == code, result.stdout
        assert sandbox.snapshot['same_system_proxy']

    def test_switch_parameter_retains_its_boolean_type(self, sandbox, probe):
        result = probe('[CmdletBinding()]param(); docker logs ([string]$VerbosePreference)', '-Verbose')
        assert result.returncode == 0, result.stdout
        assert sandbox.calls('docker') == [['logs', 'Continue']]

    def test_timeout_terminates_the_native_process(self, sandbox, windows_runner):
        """A hung hosted engine must not leave the test waiting forever."""
        from support.harness import SHELL

        sandbox.manager.write_text('Start-Sleep -Seconds 10', encoding='utf-8-sig')
        request = sandbox.windows_request(sandbox.manager, [], {})
        with pytest.raises(subprocess.TimeoutExpired):
            windows_runner(request, shell=SHELL, env=sandbox.env, timeout=1)

    @pytest.mark.parametrize('swallowed', [False, True], ids=['unhandled', 'caught'])
    def test_failed_python_helper_cannot_return_a_successful_fixture(self, sandbox, probe, swallowed):
        failure = sandbox.root / 'failed helper.py'
        failure.write_text('raise SystemExit(7)\n')
        sandbox.env['AGENT_TEST_STUB'] = str(failure)
        call = 'Invoke-RestMethod -Uri https://unused.example.com -Method Get -TimeoutSec 30'
        source = f'try {{ {call} }} catch {{}}; exit 0' if swallowed else call
        result = probe(source)
        assert result.returncode == 1, result.stdout
        assert 'Test stub Invoke-RestMethod failed (exit 7)' in result.stdout

    def test_unknown_cim_class_does_not_query_the_real_host(self, probe):
        result = probe('Get-CimInstance Win32_Processor -ErrorAction Stop')
        assert result.returncode == 1, result.stdout
        assert 'Unexpected CIM class' in result.stdout

    def test_intentional_dependency_failure_is_catchable(self, sandbox, probe):
        """Configured failures test script recovery without forcing the host's exit code."""
        sandbox.options['registry_failure'] = True
        result = probe(
            "try { Invoke-RestMethod -Uri https://unused.example.com } catch { Write-Host 'caught' }; exit 0"
        )
        assert result.returncode == 0, result.stdout
        assert 'caught' in result.stdout
        assert not (sandbox.root / 'stub-error.txt').exists()

    def test_script_exception_preserves_requested_observations(self, sandbox, probe):
        sandbox.observe_proxy = True
        result = probe("throw 'probe failure'")
        assert result.returncode == 1, result.stdout
        assert 'probe failure' in result.stdout
        assert sandbox.snapshot['same_system_proxy']
