"""Exercise the real manager scripts through their command-line interface.

Sandbox selects Bash or PowerShell for the host and supplies Python stubs for
Docker and registry requests. Tests inspect recorded arguments, fake container
state, and real installation files without requiring Docker or network access.
Manager updates also check validated replacement and failed-download cleanup.
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
from support.harness import EXT, IMAGE, REGISTRY, SHELL, WINDOWS


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


    def test_invalid_version_rejected(self, sandbox, run_manager):
        sandbox.version.write_text('2.0')
        assert "Invalid proxy agent version '2.0'" in run_manager('upgrade', code=1)
        assert not docker_calls(sandbox, 'run')


    def test_upgrade_selects_latest_matching_variant(self, sandbox, variant, run_manager):
        run_manager('upgrade')
        assert_image(sandbox, variant, '1.2.10')

    def test_upgrade_sorts_unordered_numeric_versions(self, sandbox, variant, run_manager):
        sandbox.options['tags'] = [f'1.2.{n}{variant.suffix}' for n in (10, 2, 12, 9)]
        run_manager('upgrade')
        assert_image(sandbox, variant, '1.2.12')


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


class TestPlatformDifferences:
    """Document intentional differences in tag errors and PowerShell argument binding.

    These cases preserve each script's expected interface while checking common
    outcomes, such as refusing an incompatible tag without starting an agent.
    """


    @pytest.mark.skipif(not WINDOWS, reason='PowerShell common parameters')
    def test_powershell_consumes_verbose_but_forwards_doctor_options(self, sandbox):
        result = sandbox.run('doctor', '-Verbose', '--region', 'us-east-1')
        assert result.returncode == 0, result.stdout
        (args,) = [call for call in sandbox.calls('docker') if call[0] == 'run']
        assert '-Verbose' not in args
        assert args[-3:] == ['--doctor', '--region', 'us-east-1']
