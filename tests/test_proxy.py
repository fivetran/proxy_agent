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
import os
import re
import shutil
import socket
import subprocess
import threading
import time
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
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


@pytest.mark.integration
@pytest.mark.parametrize('bypass', ['', 'host', '*'], ids=['proxy', 'bypass-host', 'bypass-all'])
def test_native_update_replaces_itself_and_migrates_an_existing_wrapper(tmp_path, web_endpoints, bypass):
    binary = tmp_path / ('manager.exe' if WINDOWS else 'manager')
    shutil.copy2(Path(os.environ['PROXY_AGENT_MANAGER_BIN']), binary)
    wrapper = tmp_path / f'proxy-agent-manager.{EXT}'
    if not bypass:
        wrapper.write_text('# old manager script\n')

    replacement = b'replacement native manager fixture'
    web_endpoints.manager_binary = replacement
    username, password = 'fixture-proxy-user', 'fixture-proxy-password'
    web_endpoints.expected_auth = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
    env = {key: value for key, value in os.environ.items() if not key.upper().endswith('_PROXY')}
    env.update(
        PROXY_AGENT_MANAGER_BINARY_URL=web_endpoints.origin_url + '/native-manager',
        PROXY_AGENT_MANAGER_BINARY_SHA256=hashlib.sha256(replacement).hexdigest(),
        NO_PROXY=web_endpoints.origin_host if bypass == 'host' else bypass,
    )
    env['HTTP_PROXY' if WINDOWS else 'http_proxy'] = web_endpoints.proxy_url.replace(
        'http://', f'http://{username}:{password}@'
    )
    result = subprocess.run([str(binary), 'update-manager'], env=env, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    assert username not in result.stdout + result.stderr
    assert password not in result.stdout + result.stderr

    # Windows completes the replacement after the original process exits.
    deadline = time.monotonic() + 15
    template = REPO / 'proxy_agent/resources/com/fivetran/proxy_agent/compat' / wrapper.name
    while time.monotonic() < deadline:
        if binary.stat().st_size == len(replacement) and (bypass or wrapper.read_text() == template.read_text()):
            break
        time.sleep(0.1)
    log = tmp_path / 'logs/manager-update.log'
    assert binary.read_bytes() == replacement, log.read_text() if log.exists() else result.stdout
    if bypass:
        assert not wrapper.exists()
        assert not web_endpoints.requests['proxy']
        assert [request['path'] for request in web_endpoints.requests['origin']] == ['/native-manager']
    else:
        assert wrapper.read_text() == template.read_text()
        assert not web_endpoints.requests['origin']
        assert sum(request['proxy_authorization'] == web_endpoints.expected_auth
                   for request in web_endpoints.requests['proxy']) == 1


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
    @pytest.mark.parametrize('operation', ['install'])
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
