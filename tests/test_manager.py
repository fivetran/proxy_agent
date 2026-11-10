"""The same management contract runs against each supported shell and container OS."""
import base64
import unittest

from support.harness import IMAGE, REGISTRY, WINDOWS, Sandbox


class ManagerTests(unittest.TestCase):
    container_os = 'linux'

    def setUp(self):
        self.s = Sandbox(self.container_os)
        self.addCleanup(self.s.close)

    @property
    def suffix(self):
        return '-windows-ltsc2022' if self.container_os == 'windows' else '-ubuntu-26.04'

    @property
    def config_path(self):
        return 'C:/config/config.json' if self.container_os == 'windows' else '/config/config.json'

    @property
    def log_dir(self):
        return 'C:/app/logs' if self.container_os == 'windows' else '/app/logs'

    def run_manager(self, *args, code=0):
        result = self.s.run(*args)
        self.assertEqual(result.returncode, code, result.stdout)
        return result.stdout

    def docker_calls(self, command):
        return [call for call in self.s.calls('docker') if call[0] == command]

    def run_args(self):
        return self.docker_calls('run')[-1]

    def assert_image(self, version='1.2.9'):
        self.assertEqual(self.s.image, f'{IMAGE}:{version}{self.suffix}')
        self.assertEqual(self.s.version.read_text().strip(), version)

    def test_start_pinned_image_memory_mounts_labels_and_healthcheck(self):
        self.run_manager('start')
        self.assert_image()
        args = self.run_args()
        for value in ('--memory=5120m', 'proxy-agent-abc123', 'proxy_agent_id=abc123',
                      'fivetran=proxy-agent', 'unless-stopped', f'LOG_FOLDER_PATH={self.log_dir}'):
            self.assertIn(value, args)
        source = self.s.agent / ('config' if self.container_os == 'windows' else 'config/config.json')
        target = 'C:/config' if self.container_os == 'windows' else self.config_path
        self.assertIn(f'{source.as_posix()}:{target}:ro', args)
        self.assertIn(f'{(self.s.agent / "logs").as_posix()}:{self.log_dir}', args)
        self.assertEqual(args[-2:], ['-i', self.config_path])
        health = args[args.index('--health-cmd') + 1]
        if self.container_os == 'windows':
            self.assertTrue(health.startswith('powershell.exe -NoProfile -EncodedCommand '))
            health = base64.b64decode(health.split()[-1]).decode('utf-16le')
        self.assertIn('HEARTBEAT_EXPIRE_AT', health)
        self.assertEqual(args[args.index('--health-timeout') + 1],
                         '15s' if self.container_os == 'windows' else '3s')

    def test_legacy_tag_normalized_on_start_and_restart(self):
        for command in ('start', 'restart'):
            with self.subTest(command=command):
                self.s.version.write_text('1.2.9' + self.suffix)
                self.run_manager(command)
                self.assert_image()

    def test_bom_and_crlf_version(self):
        self.s.version.write_bytes(b'\xef\xbb\xbf' + ('1.2.9' + self.suffix + '\r\n').encode())
        self.run_manager('start')
        self.assert_image()

    def test_custom_memory(self):
        self.s.settings(MEMORY_ALLOCATION_MB=2048)
        self.run_manager('start')
        self.assertIn('--memory=2048m', self.run_args())

    def test_invalid_memory_cannot_modify_running_agent(self):
        self.run_manager('start')
        for setting in ('4G', 0):
            self.s.settings(MEMORY_ALLOCATION_MB=setting)
            for command in ('start', 'restart', 'upgrade', 'doctor'):
                with self.subTest(setting=setting, command=command):
                    before = self.s.calls('docker')
                    output = self.run_manager(command, code=1)
                    self.assertRegex(output, r'Invalid \$?MEMORY_ALLOCATION_MB')
                    self.assertIn(str(setting), output)
                    self.assertEqual(self.s.calls('docker'), before)
                    self.assert_image()

    def test_nonstarting_commands_tolerate_invalid_settings_and_version(self):
        self.run_manager('start')
        self.s.settings(MEMORY_ALLOCATION_MB='4G')
        self.s.version.write_text('2.0')
        self.assertIn('NAMES IMAGE STATUS', self.run_manager('status'))
        self.assertIn('name=proxy-agent-abc123', self.docker_calls('ps')[-1])
        self.run_manager('logs')
        self.assertEqual(self.docker_calls('logs')[-1], ['logs', '-f', 'proxy-agent-abc123'])
        self.run_manager('stop')
        self.assertIsNone(self.s.image)
        for command in ('stop', 'rm'):
            self.assertEqual(self.docker_calls(command)[-1], [command, 'proxy-agent-abc123'])
        self.assertEqual(self.s.version.read_text(), '2.0')

    def test_missing_config(self):
        self.s.config.unlink()
        self.assertIn('Config file not found', self.run_manager('start', code=1))
        self.assertFalse(self.docker_calls('run'))

    def test_empty_version(self):
        for contents in ('', '\n', ' \t\r\n'):
            with self.subTest(contents=repr(contents)):
                self.s.version.write_bytes(contents.encode())
                self.assertIn('is empty', self.run_manager('start', code=1))
                self.assertFalse(self.s.calls('docker'))

    def test_invalid_version_rejected(self):
        self.s.version.write_text('2.0')
        self.assertIn("Invalid proxy agent version '2.0'", self.run_manager('upgrade', code=1))
        self.assertFalse(self.docker_calls('run'))

    def test_upgrade_selects_latest_matching_variant(self):
        self.run_manager('upgrade')
        self.assert_image('1.2.10')

    def test_registry_url_timeout_and_override(self):
        for url in (REGISTRY, 'http://127.0.0.1:1/v2/stub/tags/list'):
            with self.subTest(url=url):
                if url != REGISTRY:
                    self.s.env['PROXY_AGENT_REGISTRY_TAGS_URL'] = url
                self.run_manager('upgrade')
                if WINDOWS:
                    self.assertIn([url, 'Get', '30'], self.s.calls('request'))
                else:
                    request = self.s.calls('curl')[-1]
                    self.assertIn(url, request)
                    self.assertEqual(request[request.index('--max-time') + 1], '30')

    def test_upgrade_noop_normalizes_bare_and_legacy_versions(self):
        self.s.options['tags'] = ['1.2.9' + self.suffix, '1.2.11']
        for version in ('1.2.9', '1.2.9' + self.suffix):
            with self.subTest(version=version):
                self.s.version.write_text(version)
                self.assertIn('Already running the latest version (1.2.9)', self.run_manager('upgrade'))
                self.assertEqual(self.s.version.read_text().strip(), '1.2.9')
                self.assertFalse(self.docker_calls('run'))

    def test_upgrade_rollback(self):
        self.s.options['unhealthy_tag'] = '1.2.10'
        self.assertIn('rolling back', self.run_manager('upgrade', code=1))
        self.assert_image()

    def test_upgrade_reports_failed_rollback(self):
        self.s.options['unhealthy_tag'] = '1.2.'
        self.assertIn('Rollback to 1.2.9 failed', self.run_manager('upgrade', code=1))
        self.assertEqual(self.s.version.read_text().strip(), '1.2.9')

    def test_no_matching_registry_tags_does_not_start(self):
        self.s.options['tags'] = ['1.2.11', '1.2.11-windows-ltsc2016']
        self.run_manager('upgrade', code=1)
        self.assertFalse(self.docker_calls('run'))
        self.assertEqual(self.s.version.read_text().strip(), '1.2.9')

    def test_doctor_arguments_mounts_memory_and_exit_code(self):
        self.s.options['doctor_exit'] = 3
        self.run_manager('doctor', '--region', 'us-east-1', '--tcp-attempts', '3', code=3)
        args = self.run_args()
        self.assertEqual(args[:4], ['run', '--rm', '--workdir', self.log_dir])
        self.assertIn('--memory=5120m', args)
        self.assertIn(f'LOG_FOLDER_PATH={self.log_dir}', args)
        source = self.s.agent / ('config' if self.container_os == 'windows' else 'config/config.json')
        target = 'C:/config' if self.container_os == 'windows' else self.config_path
        self.assertIn(f'{source.as_posix()}:{target}:ro', args)
        self.assertIn(f'{(self.s.agent / "logs").as_posix()}:{self.log_dir}', args)
        self.assertEqual(args[-7:], [f'{IMAGE}:1.2.9{self.suffix}', self.config_path, '--doctor',
                                    '--region', 'us-east-1', '--tcp-attempts', '3'])

    def test_doctor_keeps_running_agent_and_saves_stdout_and_stderr(self):
        self.run_manager('start')
        output = self.run_manager('doctor')
        self.assert_image()
        self.assertFalse(self.docker_calls('stop'))
        self.assertFalse(self.docker_calls('rm'))
        saved, = (self.s.agent / 'logs').glob('proxy-agent-manager-doctor-*.log')
        # Native stdout/stderr can be delivered in either order by PowerShell.
        self.assertCountEqual(saved.read_text(encoding='utf-8-sig').splitlines(), ['stub doctor report', 'stub doctor log'])
        self.assertIn('stub doctor report', output)
        self.assertIn(f'output was saved to {saved}', output)

    def test_repeated_doctor_reports_preserved(self):
        # Bash's date stub fixes the timestamp; PowerShell uses a GUID as well.
        for report in ('first doctor report', 'second doctor report'):
            self.s.options['doctor_report'] = report
            self.run_manager('doctor')
        reports = list((self.s.agent / 'logs').glob('proxy-agent-manager-doctor-*.log'))
        self.assertEqual(len(reports), 2)
        contents = ''.join(path.read_text(encoding='utf-8-sig') for path in reports)
        self.assertIn('first doctor report', contents)
        self.assertIn('second doctor report', contents)

    def test_doctor_distinguishes_earlier_and_new_logs(self):
        logs = self.s.agent / 'logs'
        logs.mkdir()
        earlier = logs / 'doctor-2019-01-01.log'
        earlier.write_text('earlier report')
        self.run_manager('doctor')
        fallback, = logs.glob('proxy-agent-manager-doctor-*.log')
        fallback.unlink()
        self.s.options['doctor_log'] = True
        self.assertIn(f'{self.log_dir} in the container is {logs} on this host.', self.run_manager('doctor'))
        self.assertTrue((logs / 'doctor-stub.log').exists())
        self.assertFalse(list(logs.glob('proxy-agent-manager-doctor-*.log')))
        self.assertEqual(earlier.read_text(), 'earlier report')

    def test_unknown_command_prints_usage(self):
        self.assertIn('Usage:', self.run_manager('bogus', code=1))


@unittest.skipUnless(WINDOWS, 'Windows containers require the PowerShell manager')
class WindowsContainerTests(ManagerTests):
    # Inherited contract tests also run in Windows-container mode, including
    # rollback and doctor behavior; only the image and mount expectations differ.
    container_os = 'windows'

    def test_ltsc_detection_and_unsupported_hosts(self):
        for build, variant in ((17763, 'ltsc2019'), (20348, 'ltsc2022'), (26100, 'ltsc2025')):
            with self.subTest(build=build):
                self.s.options['build'] = build
                self.run_manager('start')
                self.assertEqual(self.s.image, f'{IMAGE}:1.2.9-windows-{variant}')
        before = self.docker_calls('run')
        self.s.options['build'] = 99999
        self.assertIn("Unsupported Windows Server build '99999'", self.run_manager('start', code=1))
        self.s.options['product_type'] = 1
        self.assertIn('Windows Server is required', self.run_manager('start', code=1))
        self.assertEqual(self.docker_calls('run'), before)

    def test_override_and_legacy_migration_preserve_memory_without_final_newline(self):
        for settings in (False, True):
            with self.subTest(settings=settings):
                path = self.s.agent / 'settings.ps1'
                path.unlink(missing_ok=True)
                if settings:
                    self.s.settings(MEMORY_ALLOCATION_MB=2048)
                self.s.version.write_text('1.2.9-windows-ltsc2025')
                self.run_manager('start')
                self.assertEqual(self.s.version.read_text().strip(), '1.2.9')
                self.assertIn("$WINDOWS_VERSION = 'ltsc2025'", path.read_text())
                if settings:
                    self.assertIn('2048\n$WINDOWS_VERSION', path.read_text())
                self.run_manager('restart')
                self.assertEqual(self.s.image, f'{IMAGE}:1.2.9-windows-ltsc2025')
                self.run_manager('upgrade')
                self.assertEqual(self.s.image, f'{IMAGE}:1.2.11-windows-ltsc2025')

    def test_settings_override_host_detection(self):
        self.s.settings(WINDOWS_VERSION='ltsc2025')
        self.run_manager('start')
        self.assertEqual(self.s.image, f'{IMAGE}:1.2.9-windows-ltsc2025')
        self.run_manager('upgrade')
        self.assertEqual(self.s.image, f'{IMAGE}:1.2.11-windows-ltsc2025')

    def test_ubuntu_legacy_tag_migrates_with_warning(self):
        self.s.version.write_text('1.2.9-ubuntu-26.04')
        self.assertRegex(self.run_manager('start'), r'WARNING: .*Linux image tag .*Windows containers')
        self.assert_image()


class PlatformDifferencesTests(unittest.TestCase):
    def test_linux_container_mode_rejects_windows_tag(self):
        s = Sandbox()
        self.addCleanup(s.close)
        s.version.write_text('1.2.9-windows-ltsc2022')
        result = s.run('start')
        self.assertEqual(result.returncode, 1, result.stdout)
        # Bash rejects this syntax; PowerShell recognizes it but rejects the
        # container mode. Both must leave the version and agent untouched.
        self.assertIn('Windows containers' if WINDOWS else 'Invalid proxy agent version', result.stdout)
        self.assertEqual(s.version.read_text(), '1.2.9-windows-ltsc2022')
        self.assertIsNone(s.image)

    @unittest.skipUnless(WINDOWS, 'PowerShell common parameters')
    def test_powershell_consumes_verbose_but_forwards_doctor_options(self):
        s = Sandbox()
        self.addCleanup(s.close)
        result = s.run('doctor', '-Verbose', '--region', 'us-east-1')
        self.assertEqual(result.returncode, 0, result.stdout)
        args, = [call for call in s.calls('docker') if call[0] == 'run']
        self.assertNotIn('-Verbose', args)
        self.assertEqual(args[-3:], ['--doctor', '--region', 'us-east-1'])
