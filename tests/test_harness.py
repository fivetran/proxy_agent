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


@pytest.mark.skipif(not WINDOWS, reason='requires the PowerShell child process')
class TestPowerShellBridge:
    """Verify the actual PowerShell-to-Python boundary under the selected shell.

    Probes exercise UTF-8 JSON forwarding, errors caught by the called script,
    and snapshots after exceptions. They require Windows, but no Docker daemon.
    """

    def test_native_arguments_preserve_quotes_empty_strings_spaces_and_unicode(self, sandbox, probe):
        arguments = ['', 'two words', 'a"b', "O'Brien", '雪', '--region']
        result = probe('docker logs @args', *arguments)
        assert result.returncode == 0, result.stdout
        assert sandbox.calls('docker') == [['logs', *arguments]]
        assert sandbox.snapshot == {}

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

    def test_script_exception_preserves_requested_observations(self, sandbox, probe):
        sandbox.observe_proxy = True
        result = probe("throw 'probe failure'")
        assert result.returncode == 1, result.stdout
        assert 'probe failure' in result.stdout
        assert sandbox.snapshot['same_system_proxy']
