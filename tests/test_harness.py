"""Regression checks for the boundary between Python and the PowerShell child."""
import json
import subprocess
import sys
import unittest

from support.harness import REPO, WINDOWS, Sandbox


class StubProtocolTests(unittest.TestCase):
    def test_unsupported_host_queries_and_malformed_arguments_fail_closed(self):
        sandbox = Sandbox()
        self.addCleanup(sandbox.close)
        (sandbox.root / 'options.json').write_text(json.dumps(sandbox.options))
        for arguments, message in (
            (['Get-CimInstance', 'Win32_Processor'], 'Unexpected CIM class'),
            (['Invoke-RestMethod', '-Uri'], 'Missing value'),
            (['Invoke-RestMethod', '-Unexpected', 'value'], 'Unexpected Invoke-RestMethod parameter'),
        ):
            with self.subTest(arguments=arguments):
                result = subprocess.run(
                    [sys.executable, str(REPO / 'tests/support/stub.py'), 'powershell-json'],
                    input=json.dumps(arguments).encode(), capture_output=True, env=sandbox.env, timeout=5,
                )
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn(message, json.loads(result.stdout)['error'])
                self.assertFalse(sandbox.calls('docker'))


@unittest.skipUnless(WINDOWS, 'requires the PowerShell child process')
class PowerShellBridgeTests(unittest.TestCase):
    def setUp(self):
        self.s = Sandbox()
        self.addCleanup(self.s.close)

    def probe(self, source, *arguments):
        self.s.manager.write_text(source, encoding='utf-8-sig')
        return self.s.run(*arguments)

    def test_native_arguments_preserve_quotes_empty_strings_spaces_and_unicode(self):
        arguments = ['', 'two words', 'a"b', "O'Brien", '雪', '--region']
        result = self.probe('docker logs @args', *arguments)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.s.calls('docker'), [['logs', *arguments]])
        self.assertEqual(self.s.snapshot, {})

    def test_failed_python_helper_cannot_return_a_successful_fixture(self):
        failure = self.s.root / 'failed helper.py'
        failure.write_text('raise SystemExit(7)\n')
        self.s.env['AGENT_TEST_STUB'] = str(failure)
        call = 'Invoke-RestMethod -Uri https://unused.example.com -Method Get -TimeoutSec 30'
        for source in (call, f'try {{ {call} }} catch {{}}; exit 0'):
            with self.subTest(source=source):
                result = self.probe(source)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn('Test stub Invoke-RestMethod failed (exit 7)', result.stdout)

    def test_unknown_cim_class_does_not_query_the_real_host(self):
        result = self.probe('Get-CimInstance Win32_Processor -ErrorAction Stop')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('Unexpected CIM class', result.stdout)

    def test_script_exception_preserves_requested_observations(self):
        self.s.observe_proxy = True
        result = self.probe("throw 'probe failure'")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('probe failure', result.stdout)
        self.assertTrue(self.s.snapshot['same_system_proxy'])
