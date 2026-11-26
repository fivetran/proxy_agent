"""Check shared proxy behavior and PowerShell's additional .NET configuration.

Shared contract tests use real curl or PowerShell web requests against local
origin/proxy servers, with Docker still stubbed. They verify routing, bypass,
authentication, and credential redaction without external network access.
Windows-only tests also inspect native .NET objects with web requests stubbed;
the source-consistency check runs everywhere.
"""

import base64
import hashlib
import json
import re
import socket
import threading
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from urllib.parse import quote, urlsplit

import pytest
from support.harness import EXT, IMAGE, REPO, TAGS, WINDOWS, Sandbox
from support.native_installer import installer_start_script


@pytest.fixture
def native_web_sandbox(web_endpoints, windows_runner):
    """Use real web clients, retaining fake Docker and an isolated curl config."""
    installation = Sandbox(stub_http=False, windows_runner=windows_runner)
    installation.env['CURL_HOME'] = str(installation.root)
    (installation.root / '.curlrc').write_text('')
    installation.env.update(
        PROXY_AGENT_REGISTRY_TAGS_URL=web_endpoints.registry_url,
        PROXY_AGENT_SCRIPT_URL=web_endpoints.script_url,
        FIVETRAN_API_URL=web_endpoints.origin_url,
    )
    try:
        yield installation
    finally:
        installation.close()


@pytest.fixture
def web_endpoints():
    """Record direct and proxied HTTP requests; optionally challenge for Basic auth.

    Both listeners serve registry tags and the checked-out manager. The proxy
    accepts absolute URLs without forwarding. The origin uses this machine's
    non-loopback IPv4 address so .NET Framework cannot bypass it automatically.
    """
    endpoints = SimpleNamespace(
        requests={'origin': [], 'proxy': []}, expected_auth=None, registry_body=None, script_body=None,
        configure_status=200, manager_binary=None,
    )
    script_path = f'/proxy-agent-manager.{EXT}'
    configure_path = '/proxy-agent/configure'
    endpoints.config = {'agent_id': 'configured-agent'}
    responses = {
        '/v2/stub/tags/list': ('application/json', json.dumps({'tags': TAGS}).encode()),
        script_path: ('text/plain', (REPO / script_path.lstrip('/')).read_bytes()),
    }

    def handler(role):
        class Handler(BaseHTTPRequestHandler):
            """Serve fixtures while recording proxy and origin authentication headers."""

            def do_GET(self):
                fixtures = responses
                if endpoints.manager_binary is not None:
                    fixtures = {**responses, '/native-manager': ('application/octet-stream', endpoints.manager_binary)}
                self.serve_response(fixtures)

            def do_POST(self):
                self.serve_response({configure_path: ('application/json', json.dumps(endpoints.config).encode())},
                                    status=endpoints.configure_status)

            def serve_response(self, fixtures, status=200):
                authorization = self.headers.get('Proxy-Authorization')
                endpoints.requests[role].append(
                    {
                        'path': self.path,
                        'proxy_authorization': authorization,
                        'authorization': self.headers.get('Authorization'),
                    }
                )
                if role == 'proxy' and endpoints.expected_auth and authorization != endpoints.expected_auth:
                    self.send_response(407)
                    self.send_header('Proxy-Authenticate', 'Basic realm="proxy-agent-test"')
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                path = urlsplit(self.path).path
                if path not in fixtures:
                    self.send_error(404)
                    return
                content_type, body = fixtures[path]
                if path == '/v2/stub/tags/list' and endpoints.registry_body is not None:
                    body = endpoints.registry_body
                if path == script_path and endpoints.script_body is not None:
                    body = endpoints.script_body
                self.send_response(status)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_arguments):
                pass

        return Handler

    # UDP connect selects an interface without sending a packet. No DNS or
    # external server is used; all HTTP traffic stays on this machine.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
        route.connect(('192.0.2.1', 9))
        origin_host = route.getsockname()[0]
    assert not origin_host.startswith('127.'), 'Proxy tests require a non-loopback local interface'
    endpoints.origin_host = origin_host
    with ExitStack() as cleanup:
        for role in ('origin', 'proxy'):
            host = origin_host if role == 'origin' else '127.0.0.1'
            server = ThreadingHTTPServer((host, 0), handler(role))
            cleanup.callback(server.server_close)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            cleanup.callback(thread.join, timeout=5)
            cleanup.callback(server.shutdown)
            setattr(endpoints, f'{role}_url', f'http://{host}:{server.server_port}')
        endpoints.registry_url = endpoints.origin_url + '/v2/stub/tags/list'
        endpoints.script_url = endpoints.origin_url + script_path
        endpoints.configure_path = configure_path
        yield endpoints


@pytest.mark.parametrize('source', ['token', 'websocket-config', 'rejected-token'])
def test_install_configures_agent(native_web_sandbox, web_endpoints, source):
    sandbox = native_web_sandbox
    web_endpoints.script_body = installer_start_script(windows=WINDOWS).encode()
    sandbox.env['NO_PROXY'] = '*'
    secret = 'fixture-configure-secret'
    token = base64.b64encode(f'abc123:{secret}'.encode()).decode()
    config_path = ''
    if source == 'websocket-config':
        sandbox.config.write_text(json.dumps({
            'agent_id': 'abc123', 'auth_token': secret, 'proxy_server_uri': 'wss://example.invalid',
        }))
        config_path = str(sandbox.config)
    else:
        sandbox.env['TOKEN'] = token
    if source == 'rejected-token':
        web_endpoints.configure_status = 401

    result = sandbox.install(ConfigPath=config_path)

    requests = web_endpoints.requests['origin']
    assert [(request['path'], request['authorization']) for request in requests if request['authorization']] == [
        (web_endpoints.configure_path, f'Basic {token}')
    ]
    transcript = result.stdout + ''.join(
        path.read_text(encoding='utf-8-sig') for path in (sandbox.installed / 'logs').glob('*.log')
    )
    assert token not in transcript
    assert secret not in transcript
    if source == 'rejected-token':
        assert result.returncode == 1, result.stdout
        assert 'HTTP 401' in result.stdout
        assert sandbox.image is None
    else:
        assert result.returncode == 0, result.stdout
        assert json.loads((sandbox.installed / 'config/config.json').read_text()) == web_endpoints.config
        assert sandbox.image == f'{IMAGE}:1.2.10-ubuntu-26.04'


class TestProxyContract:
    """Exercise the same observable proxy contract through each host's real client.

    Windows explicitly configures .NET from HTTP_PROXY; curl uses lowercase
    http_proxy for HTTP targets. NO_PROXY and the resulting behavior are shared.
    """


    @pytest.mark.parametrize('credentials', [None, ('us@er', 'p:ss')], ids=['anonymous', 'basic-auth'])
    @pytest.mark.parametrize('no_proxy', ['', 'host', '*'], ids=['proxied', 'bypass-host', 'bypass-all'])
    @pytest.mark.parametrize('operation', ['install', 'upgrade'])
    def test_routing_and_credentials(
        self, native_web_sandbox, web_endpoints, credentials, no_proxy, operation
    ):
        sandbox = native_web_sandbox
        proxy_url = web_endpoints.proxy_url
        if credentials:
            username, password = credentials
            web_endpoints.expected_auth = (
                'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
            )
            proxy_url = proxy_url.replace(
                'http://', f'http://{quote(username, safe="")}:{quote(password, safe="")}@'
            )
        sandbox.env['NO_PROXY'] = web_endpoints.origin_host if no_proxy == 'host' else no_proxy
        # curl deliberately ignores uppercase HTTP_PROXY for HTTP URLs.
        sandbox.env['HTTP_PROXY' if WINDOWS else 'http_proxy'] = proxy_url

        # Use the same destination in every case: only NO_PROXY changes routing.
        origin_url = web_endpoints.origin_url
        registry_url = origin_url + '/v2/stub/tags/list'
        script_url = origin_url + f'/proxy-agent-manager.{EXT}'
        sandbox.env.update(
            PROXY_AGENT_REGISTRY_TAGS_URL=registry_url,
            PROXY_AGENT_SCRIPT_URL=script_url,
        )
        expected_urls = {registry_url}
        if operation == 'install':
            web_endpoints.script_body = installer_start_script(windows=WINDOWS).encode()
            expected_urls.add(script_url)
            result = sandbox.install()
            installation = sandbox.installed
        else:
            result = sandbox.run(operation)
            installation = sandbox.agent

        assert result.returncode == 0, result.stdout
        if no_proxy:
            assert not web_endpoints.requests['proxy']
            assert web_endpoints.requests['origin']
            assert {request['path'] for request in web_endpoints.requests['origin']} == {
                urlsplit(url).path for url in expected_urls
            }
            assert all(request['authorization'] is None for request in web_endpoints.requests['origin'])
            assert all(request['proxy_authorization'] is None for request in web_endpoints.requests['origin'])
        else:
            assert not web_endpoints.requests['origin']
            assert web_endpoints.requests['proxy']
            assert {
                request['path']
                for request in web_endpoints.requests['proxy']
                if request['proxy_authorization'] == web_endpoints.expected_auth
            } == expected_urls
            assert all(request['authorization'] is None for request in web_endpoints.requests['proxy'])
        if credentials:
            transcript = result.stdout + ''.join(
                path.read_text(encoding='utf-8-sig') for path in (installation / 'logs').glob('*.log')
            )
            for secret in credentials:
                assert secret not in transcript
                assert quote(secret, safe='') not in transcript

    @pytest.mark.skipif(WINDOWS, reason='curl-specific CGI security behavior')
    def test_curl_ignores_uppercase_http_proxy(self, native_web_sandbox, web_endpoints):
        native_web_sandbox.env.update(
            HTTP_PROXY=web_endpoints.proxy_url,
        )

        result = native_web_sandbox.run('upgrade')

        assert result.returncode == 0, result.stdout
        assert web_endpoints.requests['origin']
        assert not web_endpoints.requests['proxy']


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
class TestWindowsProxy:
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
