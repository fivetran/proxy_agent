"""Check installer results with controlled Docker, web, and host responses.

The real installer writes files and applies real config permissions. Its
downloaded manager is replaced with a launcher that only records the start
request; manager lifecycle behavior is tested in test_manager.py. Sandbox
selects the host's script and translates installation arguments, allowing shared
assertions for both platforms without Docker or network access.
"""

import stat

import pytest
from support.harness import EXT, WINDOWS


@pytest.fixture
def install(sandbox):
    """Invoke the installer and validate its exit code before inspecting output."""

    def run(code=0, **parameters):
        result = sandbox.install(**parameters)
        assert result.returncode == code, result.stdout
        return result.stdout

    return run


@pytest.mark.parametrize('sandbox', ['linux', 'windows'], indirect=True)
class TestInstall:
    """Check version selection, config protection, settings, and startup delegation.

    The sandbox fixture parametrizes these cases over both container modes;
    Windows-container cases are skipped on Unix.
    """

    def test_selects_matching_version_copies_config_persists_memory_and_starts(self, sandbox, install):
        sandbox.env['MEMORY_ALLOCATION_MB'] = '2048'
        install()
        assert (sandbox.installed / 'version').read_text().strip() == '1.2.10'
        assert (sandbox.installed / 'config/config.json').read_bytes() == sandbox.config.read_bytes()
        settings = (sandbox.installed / f'settings.{EXT}').read_text()
        assert ('$MEMORY_ALLOCATION_MB = 2048' if WINDOWS else 'MEMORY_ALLOCATION_MB=2048') in settings
        assert 'WINDOWS_VERSION' not in settings
        assert sandbox.calls('manager') == [['start']]

    def test_config_permissions(self, sandbox, install):
        sandbox.observe_acl = True
        output = install()
        if WINDOWS:
            snapshot = sandbox.snapshot
            if snapshot['administrator']:
                assert 'Running as Administrator is not recommended' in output
            assert snapshot['acl_protected']
            assert {entry['sid'] for entry in snapshot['acl']} == {
                snapshot['user_sid'],
                'S-1-5-18',
                'S-1-5-32-544',
            }
            for entry in snapshot['acl']:
                assert entry['type'] == 'Allow'
                assert 'Read' in entry['rights']
                if entry['sid'] != snapshot['user_sid']:
                    assert 'Write' not in entry['rights']
        else:
            assert stat.S_IMODE((sandbox.installed / 'config/config.json').stat().st_mode) == 0o600

    def test_invalid_runtime_and_missing_config(self, sandbox, install):
        sandbox.env['RUNTIME'] = 'podman'
        assert "RUNTIME must be set to 'docker'" in install(code=1)
        sandbox.env['RUNTIME'] = 'docker'
        sandbox.config.unlink()
        assert 'Config file not found' in install(code=1)
        assert not sandbox.calls('manager')

    def test_no_matching_tags_does_not_start(self, sandbox, install):
        sandbox.options['tags'] = ['1.2.13', '1.2.13-windows-ltsc2016']
        install(code=1)
        assert not (sandbox.installed / 'version').exists()
        assert not sandbox.calls('manager')

    def test_registry_failure_does_not_pin_or_start(self, sandbox, install):
        sandbox.options['registry_failure'] = True
        assert 'Unable to query' in install(code=1)
        assert not (sandbox.installed / 'version').exists()
        assert not sandbox.calls('manager')

    def test_interrupted_download_preserves_existing_installation(self, sandbox, install):
        """A partial download must be removed without replacing existing files."""
        (sandbox.installed / 'config').mkdir(parents=True)
        original = {
            sandbox.manager.name: 'existing manager',
            'version': '1.2.9',
            'config/config.json': '{"agent_id":"existing"}',
        }
        for name, contents in original.items():
            (sandbox.installed / name).write_text(contents)
        sandbox.options['download_failure'] = True
        assert 'Failed to download management script' in install(code=1)
        for name, contents in original.items():
            assert (sandbox.installed / name).read_text() == contents
        assert not list(sandbox.installed.glob(sandbox.manager.name + '.*'))
        assert not sandbox.calls('manager')

    def test_downloaded_manager_failure_is_reported(self, sandbox, install):
        sandbox.options['manager_exit'] = 7
        assert 'agent failed to start' in install(code=1)
        assert sandbox.calls('manager') == [['start']]
        assert (sandbox.installed / 'version').read_text().strip() == '1.2.10'
        assert (sandbox.installed / 'config/config.json').read_bytes() == sandbox.config.read_bytes()

    def test_low_disk_space_warns_using_platform_minimum(self, sandbox, install):
        sandbox.options['free_mb'] = 1000
        output = install()
        minimum = 12288 if sandbox.options['container_os'] == 'windows' else 2048
        assert f'below the recommended minimum of {minimum}MB' in output
        if sandbox.options['container_os'] == 'linux':
            assert "Docker's data root" not in output


@pytest.mark.skipif(not WINDOWS, reason='Windows host and container selection')
@pytest.mark.parametrize('sandbox', ['windows'], indirect=True)
class TestWindowsInstall:
    """Apply the installer contract to Windows containers and server requirements.

    Fake OS and disk responses cover LTSC selection and Docker's storage drive,
    including unsupported hosts and the fallback to the installation drive.
    """

    def test_ltsc2019_detection(self, sandbox, install):
        sandbox.options['build'] = 17763
        install()
        assert (sandbox.installed / 'version').read_text().strip() == '1.2.8'

    def test_override_normalized_and_persisted_on_unknown_host(self, sandbox, install):
        sandbox.options['build'] = 99999
        output = install(WindowsVersion='LTSC2025')
        assert 'Using Windows image variant ltsc2025' in output
        assert (sandbox.installed / 'version').read_text().strip() == '1.2.11'
        assert "$WINDOWS_VERSION = 'ltsc2025'" in (sandbox.installed / 'settings.ps1').read_text()

    def test_unsupported_hosts(self, sandbox, install):
        sandbox.options['build'] = 99999
        assert "Unsupported Windows Server build '99999'" in install(code=1)
        sandbox.options['product_type'] = 1
        assert 'Windows Server is required' in install(code=1)
        assert not (sandbox.installed / 'version').exists()

    @pytest.mark.parametrize('drive_root', [False, True], ids=['directory', 'drive-root'])
    def test_docker_storage_drive_directory_and_root(self, sandbox, install, drive_root):
        data = sandbox.root / 'docker'
        data.mkdir()
        root = data.anchor if drive_root else str(data)
        sandbox.options.update(docker_root=root, free_mb=10000)
        output = install()
        drive = data.drive.rstrip(':')
        assert sandbox.calls('drive')[-1] == [drive]
        assert f'drive {drive}: (10000MB) is below the recommended minimum of 12288MB' in output
        assert 'does not exist' not in output

    def test_unknown_docker_storage_uses_install_drive(self, sandbox, install):
        sandbox.options['free_mb'] = 10000
        output = install()
        assert "Unable to determine Docker's data root" in output
        assert 'below the recommended minimum of 12288MB' in output
        assert sandbox.calls('drive')[-1] == [sandbox.installed.drive.rstrip(':')]

    def test_sufficient_docker_storage_has_no_warning(self, sandbox, install):
        sandbox.options['docker_root'] = sandbox.root.anchor
        assert 'disk space' not in install()


class TestInstallerDifferences:
    """Check deliberate platform restrictions: Bash root installs and Windows overrides."""

    @pytest.mark.skipif(not WINDOWS, reason='WindowsVersion parameter')
    def test_linux_containers_reject_windows_override(self, sandbox):
        result = sandbox.install(WindowsVersion='ltsc2022')
        assert result.returncode == 1, result.stdout
        assert '-WindowsVersion can only be used when Docker is running Windows containers' in result.stdout
        assert not (sandbox.installed / 'version').exists()

    @pytest.mark.skipif(
        WINDOWS, reason='Bash installer rejects root; Windows permits administrators with a warning'
    )
    def test_bash_rejects_root(self, sandbox):
        sandbox.options['uid'] = 0
        result = sandbox.install()
        assert result.returncode == 1, result.stdout
        assert 'should not be run as root' in result.stdout
