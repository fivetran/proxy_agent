"""Exercise the real manager scripts through their command-line interface.

Sandbox selects Bash or PowerShell for the host and supplies Python stubs for
Docker and registry requests. Tests inspect recorded arguments, fake container
state, and real installation files without requiring Docker or network access.
The shared contract runs in Linux-container mode on every host and is parametrized
for Windows-container mode on Windows. Deliberate platform differences have
separate cases below.
"""

import base64
import re
from types import SimpleNamespace

import pytest
from support.harness import IMAGE, REGISTRY, WINDOWS


@pytest.fixture
def variant(sandbox):
    """Expected image and mount conventions for the parametrized container mode."""
    windows = sandbox.options['container_os'] == 'windows'
    return SimpleNamespace(
        suffix='-windows-ltsc2022' if windows else '-ubuntu-26.04',
        config_path='C:/config/config.json' if windows else '/config/config.json',
        log_dir='C:/app/logs' if windows else '/app/logs',
    )


@pytest.fixture
def run_manager(sandbox):
    """Invoke a command and check its exit code before returning combined output."""

    def run(*arguments, code=0):
        result = sandbox.run(*arguments)
        assert result.returncode == code, result.stdout
        return result.stdout

    return run


def docker_calls(sandbox, command):
    return [call for call in sandbox.calls('docker') if call[0] == command]


def run_args(sandbox):
    return docker_calls(sandbox, 'run')[-1]


def assert_image(sandbox, variant, version='1.2.9'):
    assert sandbox.image == f'{IMAGE}:{version}{variant.suffix}'
    assert sandbox.version.read_text().strip() == version


@pytest.mark.parametrize('sandbox', ['linux', 'windows'], indirect=True)
class TestManager:
    """Verify lifecycle, version, resource, and doctor behavior shared by both scripts.

    Each test starts with an isolated installation pinned to 1.2.9. Helpers derive
    image tags and mount paths from the simulated container OS so the same assertions can
    run with different container conventions.
    """

    def test_start_pinned_image_memory_mounts_labels_and_healthcheck(self, sandbox, variant, run_manager):
        run_manager('start')
        assert_image(sandbox, variant)
        args = run_args(sandbox)
        for value in (
            '--memory=5120m',
            'proxy-agent-abc123',
            'proxy_agent_id=abc123',
            'fivetran=proxy-agent',
            'unless-stopped',
            f'LOG_FOLDER_PATH={variant.log_dir}',
        ):
            assert value in args
        source = sandbox.agent / (
            'config' if sandbox.options['container_os'] == 'windows' else 'config/config.json'
        )
        target = 'C:/config' if sandbox.options['container_os'] == 'windows' else variant.config_path
        assert f'{source.as_posix()}:{target}:ro' in args
        assert f'{(sandbox.agent / "logs").as_posix()}:{variant.log_dir}' in args
        assert args[-2:] == ['-i', variant.config_path]
        health = args[args.index('--health-cmd') + 1]
        if sandbox.options['container_os'] == 'windows':
            assert health.startswith('powershell.exe -NoProfile -EncodedCommand ')
            health = base64.b64decode(health.split()[-1]).decode('utf-16le')
        assert 'HEARTBEAT_EXPIRE_AT' in health
        assert args[args.index('--health-timeout') + 1] == (
            '15s' if sandbox.options['container_os'] == 'windows' else '3s'
        )

    @pytest.mark.parametrize('command', ['start', 'restart'])
    def test_legacy_tag_normalized_on_start_and_restart(self, sandbox, variant, run_manager, command):
        if command == 'restart':
            run_manager('start')
        sandbox.version.write_text('1.2.9' + variant.suffix)
        run_manager(command)
        assert_image(sandbox, variant)

    def test_bom_and_crlf_version(self, sandbox, variant, run_manager):
        sandbox.version.write_bytes(b'\xef\xbb\xbf' + ('1.2.9' + variant.suffix + '\r\n').encode())
        run_manager('start')
        assert_image(sandbox, variant)

    def test_custom_memory(self, sandbox, run_manager):
        sandbox.settings(MEMORY_ALLOCATION_MB=2048)
        run_manager('start')
        assert '--memory=2048m' in run_args(sandbox)

    @pytest.mark.parametrize('setting', ['4G', 0])
    @pytest.mark.parametrize('command', ['start', 'restart', 'upgrade', 'doctor'])
    def test_invalid_memory_cannot_modify_running_agent(
        self, sandbox, variant, run_manager, setting, command
    ):
        run_manager('start')
        sandbox.settings(MEMORY_ALLOCATION_MB=setting)
        before = sandbox.calls('docker')
        output = run_manager(command, code=1)
        assert re.search('Invalid \\$?MEMORY_ALLOCATION_MB', output)
        assert str(setting) in output
        assert sandbox.calls('docker') == before
        assert_image(sandbox, variant)

    def test_nonstarting_commands_tolerate_invalid_settings_and_version(self, sandbox, run_manager):
        run_manager('start')
        sandbox.settings(MEMORY_ALLOCATION_MB='4G')
        sandbox.version.write_text('2.0')
        assert 'NAMES IMAGE STATUS' in run_manager('status')
        assert 'name=proxy-agent-abc123' in docker_calls(sandbox, 'ps')[-1]
        run_manager('logs')
        assert docker_calls(sandbox, 'logs')[-1] == ['logs', '-f', 'proxy-agent-abc123']
        run_manager('stop')
        assert sandbox.image is None
        for command in ('stop', 'rm'):
            assert docker_calls(sandbox, command)[-1] == [command, 'proxy-agent-abc123']
        assert sandbox.version.read_text() == '2.0'

    def test_missing_config(self, sandbox, run_manager):
        sandbox.config.unlink()
        assert 'Config file not found' in run_manager('start', code=1)
        assert not docker_calls(sandbox, 'run')

    @pytest.mark.parametrize('contents', ['', '\n', ' \t\r\n'], ids=['zero-byte', 'newline', 'whitespace'])
    def test_empty_version(self, sandbox, run_manager, contents):
        sandbox.version.write_bytes(contents.encode())
        assert 'is empty' in run_manager('start', code=1)
        assert not sandbox.calls('docker')

    def test_invalid_version_rejected(self, sandbox, run_manager):
        sandbox.version.write_text('2.0')
        assert "Invalid proxy agent version '2.0'" in run_manager('upgrade', code=1)
        assert not docker_calls(sandbox, 'run')

    def test_upgrade_selects_latest_matching_variant(self, sandbox, variant, run_manager):
        run_manager('upgrade')
        assert_image(sandbox, variant, '1.2.10')

    @pytest.mark.parametrize(
        'url', [REGISTRY, 'http://127.0.0.1:1/v2/stub/tags/list'], ids=['default', 'override']
    )
    def test_registry_url_timeout_and_override(self, sandbox, run_manager, url):
        if url != REGISTRY:
            sandbox.env['PROXY_AGENT_REGISTRY_TAGS_URL'] = url
        run_manager('upgrade')
        if WINDOWS:
            assert [url, 'Get', '30'] in sandbox.calls('request')
        else:
            request = sandbox.calls('curl')[-1]
            assert url in request
            assert request[request.index('--max-time') + 1] == '30'

    @pytest.mark.parametrize('legacy', [False, True], ids=['bare-version', 'legacy-tag'])
    def test_upgrade_noop_normalizes_bare_and_legacy_versions(self, sandbox, variant, run_manager, legacy):
        sandbox.options['tags'] = ['1.2.9' + variant.suffix, '1.2.11']
        version = '1.2.9' + (variant.suffix if legacy else '')
        sandbox.version.write_text(version)
        assert 'Already running the latest version (1.2.9)' in run_manager('upgrade')
        assert sandbox.version.read_text().strip() == '1.2.9'
        assert not docker_calls(sandbox, 'run')

    def test_upgrade_rollback(self, sandbox, variant, run_manager):
        sandbox.options['unhealthy_tag'] = '1.2.10'
        assert 'rolling back' in run_manager('upgrade', code=1)
        assert_image(sandbox, variant)

    def test_upgrade_reports_failed_rollback(self, sandbox, run_manager):
        sandbox.options['unhealthy_tag'] = '1.2.'
        assert 'Rollback to 1.2.9 failed' in run_manager('upgrade', code=1)
        assert sandbox.version.read_text().strip() == '1.2.9'

    def test_no_matching_registry_tags_does_not_start(self, sandbox, run_manager):
        sandbox.options['tags'] = ['1.2.11', '1.2.11-windows-ltsc2016']
        run_manager('upgrade', code=1)
        assert not docker_calls(sandbox, 'run')
        assert sandbox.version.read_text().strip() == '1.2.9'

    def test_doctor_arguments_mounts_memory_and_exit_code(self, sandbox, variant, run_manager):
        sandbox.options['doctor_exit'] = 3
        run_manager('doctor', '--region', 'us-east-1', '--tcp-attempts', '3', code=3)
        args = run_args(sandbox)
        assert args[:4] == ['run', '--rm', '--workdir', variant.log_dir]
        assert '--memory=5120m' in args
        assert f'LOG_FOLDER_PATH={variant.log_dir}' in args
        source = sandbox.agent / (
            'config' if sandbox.options['container_os'] == 'windows' else 'config/config.json'
        )
        target = 'C:/config' if sandbox.options['container_os'] == 'windows' else variant.config_path
        assert f'{source.as_posix()}:{target}:ro' in args
        assert f'{(sandbox.agent / "logs").as_posix()}:{variant.log_dir}' in args
        assert args[-7:] == [
            f'{IMAGE}:1.2.9{variant.suffix}',
            variant.config_path,
            '--doctor',
            '--region',
            'us-east-1',
            '--tcp-attempts',
            '3',
        ]

    def test_doctor_keeps_running_agent_and_saves_stdout_and_stderr(self, sandbox, variant, run_manager):
        run_manager('start')
        output = run_manager('doctor')
        assert_image(sandbox, variant)
        assert not docker_calls(sandbox, 'stop')
        assert not docker_calls(sandbox, 'rm')
        (saved,) = (sandbox.agent / 'logs').glob('proxy-agent-manager-doctor-*.log')
        # Native stdout/stderr can arrive in either order under PowerShell.
        assert sorted(saved.read_text(encoding='utf-8-sig').splitlines()) == sorted(
            ['stub doctor report', 'stub doctor log']
        )
        assert 'stub doctor report' in output
        assert f'output was saved to {saved}' in output

    def test_repeated_doctor_reports_preserved(self, sandbox, run_manager):
        # Bash's date stub fixes the timestamp; PowerShell also includes a GUID.
        for report in ('first doctor report', 'second doctor report'):
            sandbox.options['doctor_report'] = report
            run_manager('doctor')
        reports = list((sandbox.agent / 'logs').glob('proxy-agent-manager-doctor-*.log'))
        assert len(reports) == 2
        contents = ''.join((path.read_text(encoding='utf-8-sig') for path in reports))
        assert 'first doctor report' in contents
        assert 'second doctor report' in contents

    def test_doctor_distinguishes_earlier_and_new_logs(self, sandbox, variant, run_manager):
        logs = sandbox.agent / 'logs'
        logs.mkdir()
        earlier = logs / 'doctor-2019-01-01.log'
        earlier.write_text('earlier report')
        run_manager('doctor')
        (fallback,) = logs.glob('proxy-agent-manager-doctor-*.log')
        fallback.unlink()
        sandbox.options['doctor_log'] = True
        assert f'{variant.log_dir} in the container is {logs} on this host.' in run_manager('doctor')
        assert (logs / 'doctor-stub.log').exists()
        assert not list(logs.glob('proxy-agent-manager-doctor-*.log'))
        assert earlier.read_text() == 'earlier report'

    def test_unknown_command_prints_usage(self, run_manager):
        assert 'Usage:' in run_manager('bogus', code=1)


@pytest.mark.skipif(not WINDOWS, reason='Windows containers require the PowerShell manager')
@pytest.mark.parametrize('sandbox', ['windows'], indirect=True)
class TestWindowsContainer:
    """Verify Windows LTSC selection, overrides, and legacy settings migration.

    Host builds and image tags are fixtures, so server selection, overrides, and
    legacy settings migration can be checked on either Windows CI runner.
    """

    @pytest.mark.parametrize('build,ltsc', [(17763, 'ltsc2019'), (20348, 'ltsc2022'), (26100, 'ltsc2025')])
    def test_ltsc_detection(self, sandbox, run_manager, build, ltsc):
        sandbox.options['build'] = build
        run_manager('start')
        assert sandbox.image == f'{IMAGE}:1.2.9-windows-{ltsc}'

    def test_unsupported_hosts(self, sandbox, run_manager):
        run_manager('start')
        before = docker_calls(sandbox, 'run')
        sandbox.options['build'] = 99999
        assert "Unsupported Windows Server build '99999'" in run_manager('start', code=1)
        sandbox.options['product_type'] = 1
        assert 'Windows Server is required' in run_manager('start', code=1)
        assert docker_calls(sandbox, 'run') == before

    @pytest.mark.parametrize('with_settings', [False, True], ids=['no-settings', 'existing-settings'])
    def test_override_and_legacy_migration_preserve_memory_without_final_newline(
        self, sandbox, run_manager, with_settings
    ):
        path = sandbox.agent / 'settings.ps1'
        path.unlink(missing_ok=True)
        if with_settings:
            sandbox.settings(MEMORY_ALLOCATION_MB=2048)
        sandbox.version.write_text('1.2.9-windows-ltsc2025')
        run_manager('start')
        assert sandbox.version.read_text().strip() == '1.2.9'
        assert "$WINDOWS_VERSION = 'ltsc2025'" in path.read_text()
        if with_settings:
            assert '2048\n$WINDOWS_VERSION' in path.read_text()
        run_manager('restart')
        assert sandbox.image == f'{IMAGE}:1.2.9-windows-ltsc2025'
        run_manager('upgrade')
        assert sandbox.image == f'{IMAGE}:1.2.11-windows-ltsc2025'

    def test_settings_override_host_detection(self, sandbox, run_manager):
        sandbox.settings(WINDOWS_VERSION='ltsc2025')
        run_manager('start')
        assert sandbox.image == f'{IMAGE}:1.2.9-windows-ltsc2025'
        run_manager('upgrade')
        assert sandbox.image == f'{IMAGE}:1.2.11-windows-ltsc2025'

    def test_ubuntu_legacy_tag_migrates_with_warning(self, sandbox, variant, run_manager):
        sandbox.version.write_text('1.2.9-ubuntu-26.04')
        assert re.search('WARNING: .*Linux image tag .*Windows containers', run_manager('start'))
        assert_image(sandbox, variant)


class TestPlatformDifferences:
    """Document intentional differences in tag errors and PowerShell argument binding.

    These cases preserve each script's expected interface while checking common
    outcomes, such as refusing an incompatible tag without starting an agent.
    """

    def test_linux_container_mode_rejects_windows_tag(self, sandbox):
        sandbox.version.write_text('1.2.9-windows-ltsc2022')
        result = sandbox.run('start')
        assert result.returncode == 1, result.stdout
        assert ('Windows containers' if WINDOWS else 'Invalid proxy agent version') in result.stdout
        assert sandbox.version.read_text() == '1.2.9-windows-ltsc2022'
        assert sandbox.image is None

    @pytest.mark.skipif(not WINDOWS, reason='PowerShell common parameters')
    def test_powershell_consumes_verbose_but_forwards_doctor_options(self, sandbox):
        result = sandbox.run('doctor', '-Verbose', '--region', 'us-east-1')
        assert result.returncode == 0, result.stdout
        (args,) = [call for call in sandbox.calls('docker') if call[0] == 'run']
        assert '-Verbose' not in args
        assert args[-3:] == ['--doctor', '--region', 'us-east-1']
