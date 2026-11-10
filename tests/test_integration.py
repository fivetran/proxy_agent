"""Exercise installation and management against a real Docker daemon.

Enabled with PROXY_AGENT_INTEGRATION=1. The suite builds a minimal heartbeat/
doctor image and serves the checked-out manager, registry tags, and config from
a local HTTP server. This checks actual HTTP and container behavior without
Fivetran credentials; network access is needed to pull the image's base layers.
Windows runs require Windows-container mode and build an image for the host's LTSC.
"""
import base64
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import threading
import unittest

from integration.registry_server import make_server
from support.harness import EXT, REPO, SHELL, WINDOWS, invoke


@unittest.skipUnless(os.environ.get('PROXY_AGENT_INTEGRATION') == '1', 'requires Docker; set PROXY_AGENT_INTEGRATION=1')
class IntegrationTests(unittest.TestCase):
    """Run a complete lifecycle, then verify TOKEN and legacy WebSocket installation.

    Steps share one daemon and fixture container, so run one integration suite
    at a time per daemon. Cleanup removes the container and stops the local
    server even when an assertion fails. Unit-test command stubs are not used.
    """

    def docker(self, *arguments, check=True):
        result = subprocess.run(['docker', *arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=600)
        if check:
            self.assertEqual(result.returncode, 0, result.stdout)
        return result.stdout.strip()

    def run_script(self, script, arguments=(), *, parameters=None, code=0):
        result = invoke(script, arguments, parameters=parameters, env=self.env, timeout=180)
        self.assertEqual(result.returncode, code, result.stdout)
        return result.stdout

    def manager(self, command, *options, directory=None, code=0):
        return self.run_script((directory or self.directory) / f'proxy-agent-manager.{EXT}',
                               [command, *options], code=code)

    def install(self, directory, config=None, *, token=None, code=0, **parameters):
        self.env.pop('TOKEN', None)
        if token:
            self.env['TOKEN'] = token
        return self.run_script(REPO / f'install.{EXT}', [config] if config else [],
                               parameters={'InstallDir': directory, **parameters}, code=code)

    def assert_running(self, version):
        self.assertEqual(self.docker('inspect', '-f', '{{.Config.Image}}', self.container),
                         f'proxy-agent-stub:{version}{self.suffix}')
        self.assertEqual(self.docker('inspect', '-f', '{{.State.Health.Status}}', self.container), 'healthy')

    def assert_version(self, version):
        self.assertEqual((self.directory / 'version').read_text().strip(), version)

    def test_install_and_lifecycle(self):
        with tempfile.TemporaryDirectory(prefix='proxy agent integration ') as temporary:
            self.directory = Path(temporary) / 'agent'
            self.container = 'proxy-agent-test-agent'
            self.env = os.environ.copy()
            for key in list(self.env):
                if key.upper().endswith('_PROXY') or key.upper() == 'TOKEN':
                    self.env.pop(key)
            ltsc = None
            if WINDOWS:
                result = subprocess.run([SHELL, '-NoProfile', '-NonInteractive', '-Command',
                                         '(Get-CimInstance Win32_OperatingSystem).BuildNumber'],
                                        capture_output=True, text=True, check=True)
                ltsc = {17763: 'ltsc2019', 20348: 'ltsc2022', 26100: 'ltsc2025'}[int(result.stdout.strip())]
            self.suffix = f'-windows-{ltsc}' if WINDOWS else '-ubuntu-26.04'
            old, new = '1.2.3', '1.2.4'
            build_args = ['--build-arg', f'BASE_TAG={ltsc}'] if WINDOWS else []
            self.docker('build', '-q', *build_args, '-f', str(REPO / f'tests/stub/Dockerfile.{"windows" if WINDOWS else "linux"}'),
                        '-t', f'proxy-agent-stub:{old}{self.suffix}', str(REPO / 'tests/stub'))
            self.docker('tag', f'proxy-agent-stub:{old}{self.suffix}', f'proxy-agent-stub:{new}{self.suffix}')
            fixtures = REPO / 'tests/integration/fixtures'
            token = base64.b64encode(b'test-agent:secret').decode()
            tags = [old + self.suffix, new + self.suffix, new, new + '-windows-ltsc2016',
                    new + ('-ubuntu-26.04' if WINDOWS else '-windows-ltsc2022')]
            server = make_server(REPO, tags, token, fixtures / 'config.json')
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            url = f'http://127.0.0.1:{server.server_port}'
            memory = '512' if WINDOWS else '256'
            self.env.update(RUNTIME='docker', MEMORY_ALLOCATION_MB=memory, PROXY_AGENT_IMAGE='proxy-agent-stub',
                            PROXY_AGENT_REGISTRY_TAGS_URL=url + '/v2/stub/tags/list',
                            PROXY_AGENT_SCRIPT_URL=url + f'/proxy-agent-manager.{EXT}', FIVETRAN_API_URL=url)
            try:
                self.install(self.directory, fixtures / 'config.json')
                self.assert_running(new)
                self.assert_version(new)
                self.assertEqual((self.directory / 'config/config.json').read_bytes(), (fixtures / 'config.json').read_bytes())
                settings = (self.directory / f'settings.{EXT}').read_text()
                self.assertIn(f'$MEMORY_ALLOCATION_MB = {memory}' if WINDOWS else f'MEMORY_ALLOCATION_MB={memory}', settings)
                self.assertNotIn('WINDOWS_VERSION', settings)
                self.assertEqual(int(self.docker('inspect', '-f', '{{.HostConfig.Memory}}', self.container)), int(memory) * 1024 ** 2)
                if not WINDOWS:
                    self.assertEqual(stat.S_IMODE((self.directory / 'config/config.json').stat().st_mode), 0o600)
                self.assertIn(self.container, self.manager('status'))
                for version in (old, old + self.suffix):
                    self.directory.joinpath('version').write_text(version)
                    self.manager('start')
                    self.assert_running(old)
                    self.assert_version(old)
                if WINDOWS:
                    self.assertIn(f"$WINDOWS_VERSION = '{ltsc}'", (self.directory / 'settings.ps1').read_text())
                self.manager('upgrade')
                self.assert_running(new)
                self.assert_version(new)
                self.assertIn('Already running the latest version', self.manager('upgrade'))
                self.assert_running(new)
                self.manager('restart')
                self.assert_running(new)
                output = self.manager('doctor', '--region', 'us-east-1', code=1)
                self.assertIn('stub doctor stderr', output)
                self.assertIn('stub doctor report for options: --region us-east-1', output)
                for name in ('doctor-stub.log', 'doctor-stub.zip'):
                    self.assertTrue((self.directory / 'logs' / name).is_file())
                self.assertFalse(list((self.directory / 'logs').glob('proxy-agent-manager-doctor-*.log')))
                self.assert_running(new)
                self.manager('stop')
                self.assertEqual(self.docker('ps', '-a', '--filter', f'name={self.container}', '--format', '{{.Names}}'), '')
                for mode in ('token', 'bad-token', 'websocket'):
                    directory = Path(temporary) / mode
                    parameters = {'WindowsVersion': ltsc} if WINDOWS and mode == 'websocket' else {}
                    output = self.install(directory, fixtures / 'config-websocket.json' if mode == 'websocket' else None,
                                          token=token if mode == 'token' else 'invalid' if mode == 'bad-token' else None,
                                          code=1 if mode == 'bad-token' else 0, **parameters)
                    config = directory / 'config/config.json'
                    if mode == 'bad-token':
                        self.assertIn('HTTP 401', output)
                        self.assertFalse(config.exists() and config.stat().st_size)
                    else:
                        self.assertEqual(config.read_bytes(), (fixtures / 'config.json').read_bytes())
                        if parameters:
                            self.assertIn(f"$WINDOWS_VERSION = '{ltsc}'", (directory / 'settings.ps1').read_text())
                        self.assert_running(new)
                        self.manager('stop', directory=directory)
            finally:
                self.docker('rm', '-f', self.container, check=False)
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)
