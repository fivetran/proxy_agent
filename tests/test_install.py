"""Check installer results with controlled Docker, web, and host responses.

The real installer writes files and applies real config permissions. Its
downloaded manager is replaced with a launcher that only records the start
request; manager lifecycle behavior is tested in test_manager.py. Sandbox
selects the host's script and translates installation arguments, allowing shared
assertions for both platforms without Docker or network access.
"""
import stat
import unittest

from support.harness import EXT, WINDOWS, Sandbox


class InstallTests(unittest.TestCase):
    """Check version selection, config protection, settings, and startup delegation.

    Linux-container fixtures run on every host. WindowsInstallTests inherits
    these cases with Windows-container fixtures and the corresponding defaults.
    """

    container_os = 'linux'
    latest = '1.2.10'

    def setUp(self):
        self.s = Sandbox(self.container_os)
        self.addCleanup(self.s.close)

    def install(self, code=0, **parameters):
        result = self.s.install(**parameters)
        self.assertEqual(result.returncode, code, result.stdout)
        return result.stdout

    def test_selects_matching_version_copies_config_persists_memory_and_starts(self):
        self.s.env['MEMORY_ALLOCATION_MB'] = '2048'
        self.install()
        self.assertEqual((self.s.installed / 'version').read_text().strip(), self.latest)
        self.assertEqual((self.s.installed / 'config/config.json').read_bytes(), self.s.config.read_bytes())
        settings = (self.s.installed / f'settings.{EXT}').read_text()
        self.assertIn('$MEMORY_ALLOCATION_MB = 2048' if WINDOWS else 'MEMORY_ALLOCATION_MB=2048', settings)
        self.assertNotIn('WINDOWS_VERSION', settings)
        self.assertEqual(self.s.calls('manager'), [['start']])

    def test_config_permissions(self):
        self.s.observe_acl = True
        output = self.install()
        if WINDOWS:
            snapshot = self.s.snapshot
            if snapshot['administrator']:
                self.assertIn('Running as Administrator is not recommended', output)
            self.assertTrue(snapshot['acl_protected'])
            self.assertEqual({entry['sid'] for entry in snapshot['acl']},
                             {snapshot['user_sid'], 'S-1-5-18', 'S-1-5-32-544'})
            for entry in snapshot['acl']:
                self.assertEqual(entry['type'], 'Allow')
                self.assertIn('Read', entry['rights'])
                if entry['sid'] != snapshot['user_sid']:
                    self.assertNotIn('Write', entry['rights'])
        else:
            self.assertEqual(stat.S_IMODE((self.s.installed / 'config/config.json').stat().st_mode), 0o600)

    def test_invalid_runtime_and_missing_config(self):
        self.s.env['RUNTIME'] = 'podman'
        self.assertIn("RUNTIME must be set to 'docker'", self.install(code=1))
        self.s.env['RUNTIME'] = 'docker'
        self.s.config.unlink()
        self.assertIn('Config file not found', self.install(code=1))
        self.assertFalse(self.s.calls('manager'))

    def test_no_matching_tags_does_not_start(self):
        self.s.options['tags'] = ['1.2.13', '1.2.13-windows-ltsc2016']
        self.install(code=1)
        self.assertFalse((self.s.installed / 'version').exists())
        self.assertFalse(self.s.calls('manager'))

    def test_low_disk_space_warns_using_platform_minimum(self):
        self.s.options['free_mb'] = 1000
        output = self.install()
        minimum = 12288 if self.container_os == 'windows' else 2048
        self.assertIn(f'below the recommended minimum of {minimum}MB', output)
        if self.container_os == 'linux':
            self.assertNotIn("Docker's data root", output)


@unittest.skipUnless(WINDOWS, 'Windows host and container selection')
class WindowsInstallTests(InstallTests):
    """Apply the installer contract to Windows containers and server requirements.

    Fake OS and disk responses cover LTSC selection and Docker's storage drive,
    including unsupported hosts and the fallback to the installation drive.
    """

    container_os = 'windows'

    def test_ltsc2019_detection(self):
        self.s.options['build'] = 17763
        self.install()
        self.assertEqual((self.s.installed / 'version').read_text().strip(), '1.2.8')

    def test_override_normalized_and_persisted_on_unknown_host(self):
        self.s.options['build'] = 99999
        output = self.install(WindowsVersion='LTSC2025')
        self.assertIn('Using Windows image variant ltsc2025', output)
        self.assertEqual((self.s.installed / 'version').read_text().strip(), '1.2.11')
        self.assertIn("$WINDOWS_VERSION = 'ltsc2025'", (self.s.installed / 'settings.ps1').read_text())

    def test_unsupported_hosts(self):
        self.s.options['build'] = 99999
        self.assertIn("Unsupported Windows Server build '99999'", self.install(code=1))
        self.s.options['product_type'] = 1
        self.assertIn('Windows Server is required', self.install(code=1))
        self.assertFalse((self.s.installed / 'version').exists())

    def test_docker_storage_drive_directory_and_root(self):
        data = self.s.root / 'docker'
        data.mkdir()
        for root in (str(data), data.anchor):
            with self.subTest(root=root):
                self.s.options.update(docker_root=root, free_mb=10000)
                output = self.install()
                drive = data.drive.rstrip(':')
                self.assertEqual(self.s.calls('drive')[-1], [drive])
                self.assertIn(f'drive {drive}: (10000MB) is below the recommended minimum of 12288MB', output)
                self.assertNotIn('does not exist', output)

    def test_unknown_docker_storage_uses_install_drive(self):
        self.s.options['free_mb'] = 10000
        output = self.install()
        self.assertIn("Unable to determine Docker's data root", output)
        self.assertIn('below the recommended minimum of 12288MB', output)
        self.assertEqual(self.s.calls('drive')[-1], [self.s.installed.drive.rstrip(':')])

    def test_sufficient_docker_storage_has_no_warning(self):
        self.s.options['docker_root'] = self.s.root.anchor
        self.assertNotIn('disk space', self.install())


class InstallerDifferencesTests(unittest.TestCase):
    """Check deliberate platform restrictions: Bash root installs and Windows overrides."""

    @unittest.skipUnless(WINDOWS, 'WindowsVersion parameter')
    def test_linux_containers_reject_windows_override(self):
        s = Sandbox()
        self.addCleanup(s.close)
        result = s.install(WindowsVersion='ltsc2022')
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('-WindowsVersion can only be used when Docker is running Windows containers', result.stdout)
        self.assertFalse((s.installed / 'version').exists())

    @unittest.skipIf(WINDOWS, 'Bash installer rejects root; Windows permits administrators with a warning')
    def test_bash_rejects_root(self):
        s = Sandbox()
        self.addCleanup(s.close)
        s.options['uid'] = 0
        result = s.install()
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn('should not be run as root', result.stdout)
