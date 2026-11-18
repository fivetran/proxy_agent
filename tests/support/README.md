# Test support

These helpers run the real scripts with fake dependencies. Assertions stay in
[the Python tests](../); unit tests need no Docker daemon or external network access.

| File | Purpose |
| --- | --- |
| [harness.py](harness.py) | Creates a temporary installation and invokes Bash or PowerShell, letting tests share inputs and assertions. |
| [stub.py](stub.py) | Implements fake Docker, HTTP, and host commands in Python. Records calls and tracks a fake running container. |

The PowerShell SDK binds arguments as .NET values. Mocked commands send UTF-8 JSON
to Python to preserve quotes, empty arguments, and Unicode under PowerShell 5.1
and 7. Broken mocks fail the test even if the script catches their exceptions;
intentional Docker failures retain their exit codes.
[conftest.py](../conftest.py) owns the Windows runner fixture, spawned process,
and cleanup. Python selects mocks and observations; native object access stays
inside that child.

## Configuration

Set `PROXY_AGENT_TEST_SHELL` before starting Python: defaults to `bash` on Unix
and `powershell` on Windows; use `pwsh` for PowerShell 7.

Request the `sandbox` fixture from [conftest.py](../conftest.py); pytest handles
cleanup. The host selects the script; indirect fixture parameters select the fake
Docker daemon's `container_os` (default `linux`). Set `sandbox.observe_proxy` or
`sandbox.observe_acl` to capture Windows state in `sandbox.snapshot`;
`sandbox.proxy_probes` supplies URLs to check.
Proxy contracts use `Sandbox(stub_http=False, windows_runner=windows_runner)`
to retain real web clients;
Docker and host commands remain fake. Other cases stub HTTP by default.

Change `sandbox.options` before an invocation:

| Options | Control |
| --- | --- |
| `container_os`, `tags` | Container mode and registry versions. |
| `build`, `product_type` | Simulated Windows build and server/client host type. |
| `free_mb`, `docker_root`, `uid` | Free disk space, Docker storage location, and Bash user ID. |
| `unhealthy_tag` | Image substring that causes health checks to fail. |
| `registry_failure`, `download_failure` | Fail registry queries or interrupt manager downloads. |
| `run_failure_tag`, `manager_exit` | Fail Docker startup for matching images, or the downloaded manager with the given exit code. |
| `configure_failure`, `configure_status`, `configure_body` | Simulate configure errors and responses. |
| `doctor_exit`, `doctor_report`, `doctor_log` | Doctor exit code, stdout, and whether it saves its own log. |
| `script_content`, `download_exit` | Downloaded manager content and failure after writing it (curl exit code; PowerShell exception). |
| `system_proxy`, `system_credentials` | Initial Windows proxy and whether it has preset credentials. |

Use `sandbox.env` for production environment variables and `settings(**values)`
for settings files. `run(...)` and `install(...)` return the exit code and merged
output; `calls(command)` and `image` expose recorded calls and container state.
See the [development instructions](../../README.md#development) for running tests.
