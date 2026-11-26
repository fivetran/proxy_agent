"""Check the script dispatch against an isolated manager."""

import shutil

import pytest
from support.harness import REPO, WINDOWS
from support.native_installer import installer_environment


def test_logs_follow_the_installed_agent(sandbox, native_command):
    result = native_command('logs')
    assert result.returncode == 0, result.stdout
    assert sandbox.calls('docker') == [['logs', '-f', 'proxy-agent-abc123']]


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


@pytest.mark.skipif(WINDOWS, reason='Uses the Bash launcher fixture')
def test_script_forwards_a_native_command_from_the_default_case(sandbox):
    launcher = sandbox.agent / 'manager'
    launcher.write_text(
        '#!/bin/sh\n'
        'if [ "$1" = future-command ] && [ "$2" = --help ]; then exit 0; fi\n'
        'printf "%s\\n" "$@"\n'
        'exit 7\n'
    )
    launcher.chmod(0o700)
    sandbox.config.unlink()

    result = sandbox.run('future-command', '--option', 'value with spaces')

    assert result.returncode == 7, result.stdout
    assert result.stdout.splitlines() == [
        '--agent-dir', str(sandbox.agent), 'future-command', '--option', 'value with spaces',
    ]
    assert not sandbox.calls('docker')
