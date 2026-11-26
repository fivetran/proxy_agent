"""Check the Java manager's command contracts with isolated installations."""

import json
import shutil

import pytest
from support.harness import REPO, WINDOWS
from support.native_installer import installer_environment, invoke_manager


@pytest.fixture
def native_command(sandbox):
    def run(*arguments):
        (sandbox.root / 'options.json').write_text(json.dumps(sandbox.options))
        return invoke_manager(REPO, sandbox.root, sandbox.env,
                              ['--agent-dir', str(sandbox.agent), *arguments], windows=WINDOWS)
    return run


def test_status_filters_for_the_installed_agent(sandbox, native_command):
    sandbox.version.write_text('invalid version')
    result = native_command('status')
    assert result.returncode == 0, result.stdout
    assert 'NAMES IMAGE STATUS' in result.stdout
    assert sandbox.calls('docker') == [
        ['ps', '-a', '--filter', 'name=proxy-agent-abc123', '--format', r'table {{.Names}}\t{{.Image}}\t{{.Status}}'],
    ]


def test_status_rejects_a_missing_config_before_running_docker(sandbox, native_command):
    sandbox.config.unlink()
    result = native_command('status')
    assert result.returncode == 1, result.stdout
    assert 'Config file not found' in result.stdout
    assert not sandbox.calls('docker')


@pytest.mark.skipif(WINDOWS, reason='Uses the Bash launcher fixture')
def test_script_delegates_only_if_the_installed_manager_supports_status(sandbox):
    launcher = sandbox.agent / 'manager'
    launcher.write_text('#!/bin/sh\nexit 1\n')
    launcher.chmod(0o700)
    first = sandbox.run('status')
    assert first.returncode == 0, first.stdout
    assert len(sandbox.calls('docker')) == 1

    env = installer_environment(REPO, sandbox.root, sandbox.env, windows=False)
    shutil.copy2(env['PROXY_AGENT_MANAGER_BIN'], launcher)
    # A valid nested ID would be accepted by the legacy Bash regex.
    sandbox.config.write_text('{"nested":{"agent_id":"abc123"}}')
    second = sandbox.run('status')
    assert second.returncode == 1, second.stdout
    assert "'agent_id' missing" in second.stdout
    assert len(sandbox.calls('docker')) == 1
