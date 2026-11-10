"""Verify the PowerShell scripts' explicit .NET proxy configuration.

Windows PowerShell 5.1 and PowerShell 7 use different default proxy APIs. The
bridge observes their real objects inside the script's process while web calls
are stubbed. Bash delegates proxy handling to curl, so these runtime checks are
Windows-only; the source-consistency check runs on every host.
"""

import re

import pytest
from support.harness import REPO, WINDOWS


@pytest.fixture
def proxy_sandbox(sandbox):
    """Opt into real .NET observations for a controlled proxy in a fresh child."""
    sandbox.observe_proxy = True
    sandbox.proxy_probes = [
        'https://us-docker.pkg.dev/v2/tags/list',
        'http://localhost:8080/',
        'https://corp.example.com/',
        'https://db.corp.example.com:5432/',
        'https://notcorp.example.com/',
    ]
    return sandbox


@pytest.fixture
def upgrade(proxy_sandbox):

    def run(code=0):
        result = proxy_sandbox.run('upgrade')
        assert result.returncode == code, result.stdout
        snapshot = proxy_sandbox.snapshot
        if 'http_client_proxy' in snapshot:
            assert snapshot['http_client_proxy']
        return (result.stdout, snapshot)

    return run


class TestProxySource:
    """Keep proxy handling consistent in two independently downloaded scripts.

    The function is duplicated so each production script works on its own.
    Only their logging calls are allowed to differ.
    """

    def test_installer_and_manager_proxy_functions_match(self):

        def body(name):
            text = (REPO / name).read_text(encoding='utf-8-sig')
            match = re.search('^function Set-WebProxyFromEnvironment \\{\\n.*?^}', text, re.M | re.S)
            assert match is not None
            return match[0].replace('Write-Log ', 'Write-Host ')

        assert body('install.ps1') == body('proxy-agent-manager.ps1')


@pytest.mark.skipif(not WINDOWS, reason='PowerShell applies environment variables to .NET proxies')
class TestProxy:
    """Inspect environment overrides, system credentials, and URL bypass decisions.

    Each invocation uses a fresh PowerShell child with a controlled system proxy.
    Requested snapshots expose credentials and object identity without leaking
    process-wide proxy changes into another test or making network requests.
    """

    def test_credentials_and_no_proxy_host_boundaries(self, proxy_sandbox, upgrade):
        proxy_sandbox.env.update(
            HTTPS_PROXY='http://us%40er:p%3Ass@proxy.example.com:3128',
            HTTP_PROXY='http://ignored.example.com:8080',
            NO_PROXY='localhost, .corp.example.com',
        )
        output, proxy = upgrade()
        assert 'Using proxy http://proxy.example.com:3128/' in output
        assert 'p%3Ass' not in output
        assert proxy['proxy_address'] == 'http://proxy.example.com:3128/'
        assert proxy['proxy_user'] == 'us@er'
        assert proxy['proxy_password'] == 'p:ss'
        assert proxy['bypass'] == {
            'https://us-docker.pkg.dev/v2/tags/list': False,
            'http://localhost:8080/': True,
            'https://corp.example.com/': True,
            'https://db.corp.example.com:5432/': True,
            'https://notcorp.example.com/': False,
        }

    def test_http_proxy_fallback_uses_windows_credentials(self, proxy_sandbox, upgrade):
        proxy_sandbox.env['HTTP_PROXY'] = 'http://proxy.example.com:8080'
        _, proxy = upgrade()
        assert proxy['proxy_address'] == 'http://proxy.example.com:8080/'
        assert proxy['default_credentials']

    def test_no_proxy_asterisk(self, proxy_sandbox, upgrade):
        proxy_sandbox.env.update(HTTPS_PROXY='http://proxy.example.com:3128', NO_PROXY='*')
        _, proxy = upgrade()
        assert all(proxy['bypass'].values())

    def test_system_proxy_preserved_and_reported(self, proxy_sandbox, upgrade):
        proxy_sandbox.options['system_proxy'] = 'http://system-proxy.example.com:8080'
        output, proxy = upgrade()
        assert 'Using system proxy http://system-proxy.example.com:8080' in output
        assert proxy['same_system_proxy']
        assert proxy['default_credentials']

    def test_system_credentials_preserved(self, proxy_sandbox, upgrade):
        proxy_sandbox.options.update(
            system_proxy='http://system-proxy.example.com:8080', system_credentials=True
        )
        _, proxy = upgrade()
        assert proxy['same_credentials']

    def test_system_proxy_credentials_not_logged(self, proxy_sandbox, upgrade):
        proxy_sandbox.options['system_proxy'] = 'http://user:secret@system-proxy.example.com:8080'
        output, _ = upgrade()
        assert 'Using system proxy http://system-proxy.example.com:8080' in output
        assert 'secret' not in output

    def test_direct_connection_not_reported(self, upgrade):
        output, _ = upgrade()
        assert not re.search('Using (system )?proxy', output)

    def test_invalid_proxy_fails_before_registry_without_leaking_credentials(self, proxy_sandbox, upgrade):
        proxy_sandbox.env['HTTPS_PROXY'] = 'http://user:secret@bad host:'
        output, _ = upgrade(code=1)
        assert 'Invalid proxy URL' in output
        assert 'secret' not in output
        assert not proxy_sandbox.calls('request')
