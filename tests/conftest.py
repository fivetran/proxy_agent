"""Shared pytest fixtures for isolated script installations.

Tests use the Linux-container sandbox by default. Shared contracts parametrize
this fixture indirectly to also run with Windows-container fixtures on Windows.
Each case owns its installation and cleans it up even when an assertion fails.
"""

import pytest
from support.harness import WINDOWS, Sandbox


@pytest.fixture
def sandbox(request):
    """Create one installation; an indirect parameter selects its container OS."""
    container_os = getattr(request, 'param', 'linux')
    if container_os == 'windows' and (not WINDOWS):
        pytest.skip('Windows containers require the PowerShell scripts')
    installation = Sandbox(container_os)
    try:
        yield installation
    finally:
        installation.close()
