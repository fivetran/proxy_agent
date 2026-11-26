"""Check the script dispatch against an isolated manager."""

import pytest
from support.harness import WINDOWS


@pytest.mark.skipif(WINDOWS, reason='Uses the Bash launcher fixture')
def test_wrapper_forwards_arguments_and_exit_status(sandbox):
    launcher = sandbox.agent / 'manager'
    launcher.write_text(
        '#!/bin/sh\n'
        'printf "%s\\n" "$@"\n'
        'exit 7\n'
    )
    launcher.chmod(0o700)
    sandbox.config.unlink()

    result = sandbox.run('future-command', '--option', 'value with spaces')

    assert result.returncode == 7, result.stdout
    assert result.stdout.splitlines() == ['future-command', '--option', 'value with spaces']
    assert not sandbox.calls('docker')
