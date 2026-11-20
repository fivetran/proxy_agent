"""Exercise installation and management against a real Docker daemon.

The suite builds a minimal heartbeat/
doctor image and serves the checked-out manager, registry tags, and config from
a local HTTP server. This checks actual HTTP and container behavior without
Fivetran credentials; network access is needed to pull the image's base layers.
Windows runs require Windows-container mode and build an image for the host's LTSC.
"""

import base64
import os
import stat
import subprocess
import threading
from types import SimpleNamespace

import pytest
from integration.registry_server import make_server
from support.harness import EXT, REPO, SHELL, WINDOWS, invoke

pytestmark = pytest.mark.integration


class TestIntegration:
    """Verify lifecycle and independent TOKEN/WebSocket installation scenarios.

    Steps share one daemon and fixture container, so run one integration suite
    at a time per daemon. Cleanup removes the container and stops the local
    server even when an assertion fails. Unit-test command stubs are not used.
    """

    old, new = ('1.2.3', '1.2.4')
    fixtures = REPO / 'tests/integration/fixtures'
    token = base64.b64encode(b'test-agent:secret').decode()

    def docker(self, *arguments, check=True):
        result = subprocess.run(
            ['docker', *arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600
        )
        if check:
            assert result.returncode == 0, result.stdout
        return result.stdout.strip()

    def run_script(self, script, arguments=(), *, parameters=None, code=0):
        result = invoke(script, arguments, parameters=parameters, env=self.env, timeout=180)
        assert result.returncode == code, result.stdout
        return result.stdout

    def manager(self, command, *options, directory=None, code=0):
        return self.run_script(
            (directory or self.directory) / f'proxy-agent-manager.{EXT}', [command, *options], code=code
        )

    def install(self, directory, config=None, *, token=None, code=0, **parameters):
        self.env.pop('TOKEN', None)
        if token:
            self.env['TOKEN'] = token
        return self.run_script(
            REPO / f'install.{EXT}',
            [config] if config else [],
            parameters={'InstallDir': directory, **parameters},
            code=code,
        )

    def assert_running(self, version):
        assert (
            self.docker('inspect', '-f', '{{.Config.Image}}', self.container)
            == f'proxy-agent-stub:{version}{self.suffix}'
        )
        assert self.docker('inspect', '-f', '{{.State.Health.Status}}', self.container) == 'healthy'

    def assert_version(self, version):
        assert (self.directory / 'version').read_text().strip() == version

    @pytest.fixture(scope='class')
    def images(self):
        """Build and tag the stub image once for all independent workflows."""
        ltsc = None
        if WINDOWS:
            result = subprocess.run(
                [
                    SHELL,
                    '-NoProfile',
                    '-NonInteractive',
                    '-Command',
                    '(Get-CimInstance Win32_OperatingSystem).BuildNumber',
                ],
                capture_output=True,
                text=True,
                check=True,
            )
            ltsc = {17763: 'ltsc2019', 20348: 'ltsc2022', 26100: 'ltsc2025'}[int(result.stdout.strip())]
        suffix = f'-windows-{ltsc}' if WINDOWS else '-ubuntu-26.04'
        old, new = (self.old, self.new)
        build_args = ['--build-arg', f'BASE_TAG={ltsc}'] if WINDOWS else []
        self.docker(
            'build',
            '-q',
            *build_args,
            '-f',
            str(REPO / f'tests/stub/Dockerfile.{("windows" if WINDOWS else "linux")}'),
            '-t',
            f'proxy-agent-stub:{old}{suffix}',
            str(REPO / 'tests/stub'),
        )
        self.docker('tag', f'proxy-agent-stub:{old}{suffix}', f'proxy-agent-stub:{new}{suffix}')
        return SimpleNamespace(ltsc=ltsc, suffix=suffix, memory='512' if WINDOWS else '256')

    @pytest.fixture
    def agent(self, tmp_path, images):
        """Own Docker and HTTP resources for one workflow, including setup failures."""
        self.root = tmp_path / 'proxy agent integration'
        self.root.mkdir()
        self.directory = self.root / 'agent'
        self.container = 'proxy-agent-test-agent'
        self.server = self.thread = None
        try:
            self.env = os.environ.copy()
            for key in list(self.env):
                if key.upper().endswith('_PROXY') or key.upper() == 'TOKEN':
                    self.env.pop(key)
            self.ltsc, self.suffix, self.memory = images.ltsc, images.suffix, images.memory
            old, new = self.old, self.new
            fixtures = self.fixtures
            token = self.token
            tags = [
                old + self.suffix,
                new + self.suffix,
                new,
                new + '-windows-ltsc2016',
                new + ('-ubuntu-26.04' if WINDOWS else '-windows-ltsc2022'),
            ]
            self.server = make_server(REPO, tags, token, fixtures / 'config.json')
            self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            url = f'http://127.0.0.1:{self.server.server_port}'
            self.env.update(
                RUNTIME='docker',
                MEMORY_ALLOCATION_MB=self.memory,
                PROXY_AGENT_IMAGE='proxy-agent-stub',
                PROXY_AGENT_REGISTRY_TAGS_URL=url + '/v2/stub/tags/list',
                PROXY_AGENT_SCRIPT_URL=url + f'/proxy-agent-manager.{EXT}',
                FIVETRAN_API_URL=url,
            )
            yield self
        finally:
            self.docker('rm', '-f', self.container, check=False)
            if self.server:
                if self.thread and self.thread.is_alive():
                    self.server.shutdown()
                    self.thread.join(timeout=5)
                self.server.server_close()

    def test_install_and_lifecycle(self, agent):
        old, new = (agent.old, agent.new)
        fixtures = agent.fixtures
        ltsc, memory = (agent.ltsc, agent.memory)
        agent.install(agent.directory, fixtures / 'config.json')
        agent.assert_running(new)
        agent.assert_version(new)
        assert (agent.directory / 'config/config.json').read_bytes() == (
            fixtures / 'config.json'
        ).read_bytes()
        settings = (agent.directory / f'settings.{EXT}').read_text()
        assert (
            f'$MEMORY_ALLOCATION_MB = {memory}' if WINDOWS else f'MEMORY_ALLOCATION_MB={memory}'
        ) in settings
        assert 'WINDOWS_VERSION' not in settings
        assert (
            int(agent.docker('inspect', '-f', '{{.HostConfig.Memory}}', agent.container))
            == int(memory) * 1024**2
        )
        if not WINDOWS:
            assert stat.S_IMODE((agent.directory / 'config/config.json').stat().st_mode) == 0o600
        assert agent.container in agent.manager('status')
        for version in (old, old + agent.suffix):
            agent.directory.joinpath('version').write_text(version)
            agent.manager('start')
            agent.assert_running(old)
            agent.assert_version(old)
        if WINDOWS:
            assert f"$WINDOWS_VERSION = '{ltsc}'" in (agent.directory / 'settings.ps1').read_text()
        agent.manager('upgrade')
        agent.assert_running(new)
        agent.assert_version(new)
        assert 'Already running the latest version' in agent.manager('upgrade')
        agent.assert_running(new)
        agent.manager('restart')
        agent.assert_running(new)
        output = agent.manager('doctor', '--region', 'us-east-1', code=1)
        assert 'stub doctor stderr' in output
        assert 'stub doctor report for options: --region us-east-1' in output
        for name in ('doctor-stub.log', 'doctor-stub.zip'):
            assert (agent.directory / 'logs' / name).is_file()
        assert not list((agent.directory / 'logs').glob('proxy-agent-manager-doctor-*.log'))
        agent.assert_running(new)
        agent.manager('stop')
        assert agent.docker('ps', '-a', '--filter', f'name={agent.container}', '--format', '{{.Names}}') == ''

    @pytest.mark.parametrize('mode', ['token', 'bad-token', 'websocket'])
    def test_installation_modes(self, agent, mode):
        """Each authentication/migration mode gets its own installation and cleanup."""
        directory = agent.root / mode
        parameters = {'WindowsVersion': agent.ltsc} if WINDOWS and mode == 'websocket' else {}
        output = agent.install(
            directory,
            agent.fixtures / 'config-websocket.json' if mode == 'websocket' else None,
            token=agent.token if mode == 'token' else 'invalid' if mode == 'bad-token' else None,
            code=1 if mode == 'bad-token' else 0,
            **parameters,
        )
        config = directory / 'config/config.json'
        if mode == 'bad-token':
            assert 'HTTP 401' in output
            assert not (config.exists() and config.stat().st_size)
        else:
            assert config.read_bytes() == (agent.fixtures / 'config.json').read_bytes()
            if parameters:
                assert f"$WINDOWS_VERSION = '{agent.ltsc}'" in (directory / 'settings.ps1').read_text()
            agent.assert_running(agent.new)
            agent.manager('stop', directory=directory)

    def test_upgrade_rolls_back_after_real_docker_pull_failure(self, agent):
        """Docker fails against a local registry; rollback must restore the old image."""
        agent.install(agent.directory, agent.fixtures / 'config.json')
        image = f'127.0.0.1:{agent.server.server_port}/proxy-agent-stub'
        original = f'proxy-agent-stub:{agent.old}{agent.suffix}'
        local_old = f'{image}:{agent.old}{agent.suffix}'
        agent.docker('tag', original, local_old)
        try:
            agent.env['PROXY_AGENT_IMAGE'] = image
            agent.directory.joinpath('version').write_text(agent.old)
            agent.manager('start')
            output = agent.manager('upgrade', code=1)
            assert 'rolling back' in output
            agent.assert_version(agent.old)
            assert agent.docker('inspect', '-f', '{{.Config.Image}}', agent.container) == local_old
            assert agent.docker('inspect', '-f', '{{.State.Health.Status}}', agent.container) == 'healthy'
        finally:
            agent.docker('image', 'rm', local_old, check=False)
