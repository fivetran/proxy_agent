# Tests

`pytest` discovers the suites below; there is no single entry-point file.
Start with `test_manager.py` for the shared script behavior.

| File | Coverage |
| --- | --- |
| [test_manager.py](test_manager.py) | Start, stop, restart, upgrade, rollback, version handling, and doctor reports. |
| [test_install.py](test_install.py) | Installation, version selection, settings, permissions, and host checks. |
| [test_proxy.py](test_proxy.py) | Shared HTTP proxy routing, authentication, and bypasses; Windows .NET proxy setup. |
| [test_harness.py](test_harness.py) | Stub failures and Python/PowerShell argument and observation handling. |
| [test_integration.py](test_integration.py) | Installation and lifecycle against real Docker and a local HTTP server. |

[conftest.py](conftest.py) provides the sandbox fixture and its cleanup.
[support/](support/README.md) contains the harness, Python command stubs, and
PowerShell bridge. [integration/](integration/) contains the HTTP server and
config fixtures; [stub/](stub/) contains the minimal Docker images used in integration tests.

## Running

From the repository root, using Python 3.10+ (`python3` if needed):

```sh
python -m pip install -r tests/requirements.txt
python -m pytest -v
python -m pytest tests/test_manager.py -v
```

Unit tests need no Docker or external network. Proxy cases use local HTTP servers
and require real curl on Unix or PowerShell web clients on Windows. The host selects Bash or PowerShell;
`PROXY_AGENT_TEST_SHELL` selects the executable (defaults to `bash` on Unix,
`powershell` on Windows; use `pwsh` for PowerShell 7). Windows-only cases are
skipped on Unix, and common tests run in both simulated container modes on Windows.

Set `PROXY_AGENT_INTEGRATION=1` to enable Docker integration tests. These require
a running Docker daemon, network access for base images, and Windows-container
mode on Windows Server. See the [development instructions](../README.md#development)
for integration commands and the CI matrix.
