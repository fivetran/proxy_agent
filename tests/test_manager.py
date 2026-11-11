"""Exercise the real manager scripts through their command-line interface.

Sandbox selects Bash or PowerShell for the host and supplies Python stubs for
Docker and registry requests. Tests inspect recorded arguments, fake container
state, and real installation files without requiring Docker or network access.
The shared contract runs in Linux-container mode on every host and is parametrized
for Windows-container mode on Windows. Deliberate platform differences have
separate cases below.
"""

import base64
import os
import re
import subprocess
import time
from types import SimpleNamespace

import pytest
from support.harness import IMAGE, REGISTRY, SHELL, WINDOWS


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


def assert_options(arguments, pairs):
    """Require adjacent option/value pairs without depending on option order."""
    actual = list(zip(arguments, arguments[1:]))
    for pair in pairs:
        assert pair in actual


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
        assert '--memory=5120m' in args
        heartbeat = (
            'C:/app/logs/proxy-agent-heartbeat.txt'
            if sandbox.options['container_os'] == 'windows'
            else '/tmp/proxy-agent-heartbeat.txt'
        )
        assert_options(
            args,
            [
                ('--name', 'proxy-agent-abc123'),
                ('--label', 'proxy_agent_id=abc123'),
                ('--label', 'fivetran=proxy-agent'),
                ('--restart', 'unless-stopped'),
                ('--env', 'IS_DOCKER=true'),
                ('--env', f'LOG_FOLDER_PATH={variant.log_dir}'),
                ('--env', f'HEARTBEAT_PATH={heartbeat}'),
                ('--env', 'HEARTBEAT_EXPIRY_SECONDS=30'),
                ('--health-interval', '10s'),
                ('--health-retries', '3'),
                ('--health-start-period', '30s'),
            ],
        )
        source = sandbox.agent / (
            'config' if sandbox.options['container_os'] == 'windows' else 'config/config.json'
        )
        target = 'C:/config' if sandbox.options['container_os'] == 'windows' else variant.config_path
        assert_options(
            args,
            [
                ('-v', f'{source.as_posix()}:{target}:ro'),
                ('-v', f'{(sandbox.agent / "logs").as_posix()}:{variant.log_dir}'),
            ],
        )
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

    @pytest.mark.parametrize(
        'config,known_bash_gap',
        [
            pytest.param('{"agent_id":"abc123"', True, id='malformed'),
            pytest.param('{"nested":{"agent_id":"abc123"}}', True, id='nested-id'),
            pytest.param('{}', False, id='missing-id'),
            pytest.param('{"agent_id":null}', False, id='null-id'),
        ],
    )
    def test_invalid_config_cannot_start(self, sandbox, config, known_bash_gap, request):
        """Only a valid config with a top-level agent ID may reach Docker."""
        sandbox.config.write_text(config)
        result = sandbox.run('start')
        if known_bash_gap and not WINDOWS:
            request.node.add_marker(
                pytest.mark.xfail(reason='Bash extracts fields without parsing JSON', strict=True)
            )
        assert result.returncode != 0, result.stdout
        assert not sandbox.calls('docker')

    @pytest.mark.parametrize('contents', ['', '\n', ' \t\r\n'], ids=['zero-byte', 'newline', 'whitespace'])
    def test_empty_version(self, sandbox, run_manager, contents):
        sandbox.version.write_bytes(contents.encode())
        assert 'is empty' in run_manager('start', code=1)
        assert not sandbox.calls('docker')

    def test_invalid_version_rejected(self, sandbox, run_manager):
        sandbox.version.write_text('2.0')
        assert "Invalid proxy agent version '2.0'" in run_manager('upgrade', code=1)
        assert not docker_calls(sandbox, 'run')

    @pytest.mark.parametrize(
        'version,known_bash_gap',
        [
            pytest.param('1.2. 9', True, id='embedded-space'),
            pytest.param('1.2.\n9', True, id='multiple-lines'),
            pytest.param('1.2.9-beta', False, id='invalid-suffix'),
        ],
    )
    def test_invalid_version_never_starts(self, sandbox, version, known_bash_gap, request):
        sandbox.version.write_text(version)
        result = sandbox.run('start')
        if known_bash_gap and not WINDOWS:
            request.node.add_marker(
                pytest.mark.xfail(reason='Bash strips whitespace inside versions', strict=True)
            )
        assert result.returncode != 0, result.stdout
        assert not docker_calls(sandbox, 'run')

    def test_surrounding_version_whitespace_is_accepted(self, sandbox, variant, run_manager):
        sandbox.version.write_text(' \t1.2.9 \r\n')
        run_manager('start')
        assert_image(sandbox, variant)

    def test_upgrade_selects_latest_matching_variant(self, sandbox, variant, run_manager):
        run_manager('upgrade')
        assert_image(sandbox, variant, '1.2.10')

    def test_upgrade_sorts_unordered_numeric_versions(self, sandbox, variant, run_manager):
        sandbox.options['tags'] = [f'1.2.{n}{variant.suffix}' for n in (10, 2, 12, 9)]
        run_manager('upgrade')
        assert_image(sandbox, variant, '1.2.12')

    def test_empty_registry_tags_preserve_running_agent(self, sandbox, variant, run_manager):
        run_manager('start')
        before = sandbox.calls('docker')
        sandbox.options['tags'] = []
        result = sandbox.run('upgrade')
        assert result.returncode != 0, result.stdout
        assert not [
            call for call in sandbox.calls('docker')[len(before) :] if call[0] in ('run', 'stop', 'rm')
        ]
        assert_image(sandbox, variant)

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

    def test_registry_failure_preserves_running_agent(self, sandbox, variant, run_manager):
        """A failed lookup must not stop, replace, or repin the running agent."""
        run_manager('start')
        before = sandbox.calls('docker')
        sandbox.options['registry_failure'] = True
        assert 'Unable to query image registry' in run_manager('upgrade', code=1)
        assert not [
            call for call in sandbox.calls('docker')[len(before) :] if call[0] in ('run', 'stop', 'rm')
        ]
        assert_image(sandbox, variant)

    def test_docker_start_failure_is_reported(self, sandbox, run_manager):
        """Bash propagates Docker's status; PowerShell normalizes startup failure to 1."""
        sandbox.options['run_failure_tag'] = '1.2.9'
        run_manager('start', code=1 if WINDOWS else 125)
        assert sandbox.image is None
        assert sandbox.version.read_text().strip() == '1.2.9'

    def test_upgrade_rolls_back_when_docker_cannot_start_new_image(self, sandbox, variant, run_manager):
        run_manager('start')
        sandbox.options['run_failure_tag'] = '1.2.10'
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
        assert_options(args, [('--env', 'IS_DOCKER=true'), ('--env', f'LOG_FOLDER_PATH={variant.log_dir}')])
        source = sandbox.agent / (
            'config' if sandbox.options['container_os'] == 'windows' else 'config/config.json'
        )
        target = 'C:/config' if sandbox.options['container_os'] == 'windows' else variant.config_path
        assert_options(
            args,
            [
                ('-v', f'{source.as_posix()}:{target}:ro'),
                ('-v', f'{(sandbox.agent / "logs").as_posix()}:{variant.log_dir}'),
            ],
        )
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


@pytest.mark.parametrize('sandbox', ['windows' if WINDOWS else 'linux'], indirect=True)
class TestHealthcheck:
    """Execute the generated health command for the host's native container OS.

    Docker is stubbed only to obtain the command. The real shell evaluates it
    against heartbeat files, so a command that always succeeds cannot pass.
    """

    @pytest.mark.parametrize('state', ['missing', 'fresh', 'expired', 'malformed', 'empty'])
    def test_heartbeat_status(self, sandbox, run_manager, tmp_path, state):
        run_manager('start')
        args = run_args(sandbox)
        health = args[args.index('--health-cmd') + 1]
        heartbeat = tmp_path / 'heartbeat.txt'
        if state != 'missing':
            contents = {
                'fresh': f'HEARTBEAT_EXPIRE_AT={int(time.time()) + 3600}\n',
                'expired': f'HEARTBEAT_EXPIRE_AT={int(time.time()) - 3600}\n',
                'malformed': 'HEARTBEAT_EXPIRE_AT=not-a-timestamp\n',
                'empty': '',
            }
            heartbeat.write_text(contents[state])
        env = os.environ.copy()
        env.pop('HEARTBEAT_EXPIRE_AT', None)
        env['HEARTBEAT_PATH'] = str(heartbeat)
        if WINDOWS:
            assert health.startswith('powershell.exe -NoProfile -EncodedCommand ')
            command = [SHELL, '-NoProfile', '-NonInteractive', '-EncodedCommand', health.split()[-1]]
        else:
            command = ['/bin/sh', '-c', health]
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=10)
        assert (result.returncode == 0) == (state in ('missing', 'fresh')), result.stdout + result.stderr


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
