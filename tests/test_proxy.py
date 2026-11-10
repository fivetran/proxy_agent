"""Verify the PowerShell scripts' explicit .NET proxy configuration.

Windows PowerShell 5.1 and PowerShell 7 use different default proxy APIs. The
bridge observes their real objects inside the script's process while web calls
are stubbed. Bash delegates proxy handling to curl, so these runtime checks are
Windows-only; the source-consistency check runs on every host.
"""
import re
import unittest

from support.harness import REPO, WINDOWS, Sandbox


class ProxySourceTests(unittest.TestCase):
    """Keep proxy handling consistent in two independently downloaded scripts.

    The function is duplicated so each production script works on its own.
    Only their logging calls are allowed to differ.
    """

    def test_installer_and_manager_proxy_functions_match(self):
        def body(name):
            text = (REPO / name).read_text(encoding='utf-8-sig')
            match = re.search(r'^function Set-WebProxyFromEnvironment \{\n.*?^}', text, re.M | re.S)
            self.assertIsNotNone(match)
            return match[0].replace('Write-Log ', 'Write-Host ')
        self.assertEqual(body('install.ps1'), body('proxy-agent-manager.ps1'))


@unittest.skipUnless(WINDOWS, 'PowerShell applies environment variables to .NET proxies')
class ProxyTests(unittest.TestCase):
    """Inspect environment overrides, system credentials, and URL bypass decisions.

    Each invocation uses a fresh PowerShell child with a controlled system proxy.
    Requested snapshots expose credentials and object identity without leaking
    process-wide proxy changes into another test or making network requests.
    """

    def setUp(self):
        self.s = Sandbox(observe_proxy=True)
        self.s.proxy_probes = [
            'https://us-docker.pkg.dev/v2/tags/list',
            'http://localhost:8080/',
            'https://corp.example.com/',
            'https://db.corp.example.com:5432/',
            'https://notcorp.example.com/',
        ]
        self.addCleanup(self.s.close)

    def upgrade(self, code=0):
        result = self.s.run('upgrade')
        self.assertEqual(result.returncode, code, result.stdout)
        snapshot = self.s.snapshot
        if 'http_client_proxy' in snapshot:
            self.assertTrue(snapshot['http_client_proxy'])
        return result.stdout, snapshot

    def test_credentials_and_no_proxy_host_boundaries(self):
        self.s.env.update(HTTPS_PROXY='http://us%40er:p%3Ass@proxy.example.com:3128',
                          HTTP_PROXY='http://ignored.example.com:8080', NO_PROXY='localhost, .corp.example.com')
        output, proxy = self.upgrade()
        self.assertIn('Using proxy http://proxy.example.com:3128/', output)
        self.assertNotIn('p%3Ass', output)
        self.assertEqual(proxy['proxy_address'], 'http://proxy.example.com:3128/')
        self.assertEqual(proxy['proxy_user'], 'us@er')
        self.assertEqual(proxy['proxy_password'], 'p:ss')
        self.assertEqual(proxy['bypass'], {
            'https://us-docker.pkg.dev/v2/tags/list': False,
            'http://localhost:8080/': True,
            'https://corp.example.com/': True,
            'https://db.corp.example.com:5432/': True,
            'https://notcorp.example.com/': False,
        })

    def test_http_proxy_fallback_uses_windows_credentials(self):
        self.s.env['HTTP_PROXY'] = 'http://proxy.example.com:8080'
        _, proxy = self.upgrade()
        self.assertEqual(proxy['proxy_address'], 'http://proxy.example.com:8080/')
        self.assertTrue(proxy['default_credentials'])

    def test_no_proxy_asterisk(self):
        self.s.env.update(HTTPS_PROXY='http://proxy.example.com:3128', NO_PROXY='*')
        _, proxy = self.upgrade()
        self.assertTrue(all(proxy['bypass'].values()))

    def test_system_proxy_preserved_and_reported(self):
        self.s.options['system_proxy'] = 'http://system-proxy.example.com:8080'
        output, proxy = self.upgrade()
        self.assertIn('Using system proxy http://system-proxy.example.com:8080', output)
        self.assertTrue(proxy['same_system_proxy'])
        self.assertTrue(proxy['default_credentials'])

    def test_system_credentials_preserved(self):
        self.s.options.update(system_proxy='http://system-proxy.example.com:8080', system_credentials=True)
        _, proxy = self.upgrade()
        self.assertTrue(proxy['same_credentials'])

    def test_system_proxy_credentials_not_logged(self):
        self.s.options['system_proxy'] = 'http://user:secret@system-proxy.example.com:8080'
        output, _ = self.upgrade()
        self.assertIn('Using system proxy http://system-proxy.example.com:8080', output)
        self.assertNotIn('secret', output)

    def test_direct_connection_not_reported(self):
        output, _ = self.upgrade()
        self.assertNotRegex(output, r'Using (system )?proxy')

    def test_invalid_proxy_fails_before_registry_without_leaking_credentials(self):
        self.s.env['HTTPS_PROXY'] = 'http://user:secret@bad host:'
        output, _ = self.upgrade(code=1)
        self.assertIn('Invalid proxy URL', output)
        self.assertNotIn('secret', output)
        self.assertFalse(self.s.calls('request'))
