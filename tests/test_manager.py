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
