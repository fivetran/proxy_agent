# Test support

These helpers run the real installer and manager scripts with controlled external
dependencies. Test cases and assertions live in [`tests/test_*.py`](../).
Unit tests require Python 3.10+ and the shell being tested, but no Python packages,
Docker daemon, or network access. See the [development instructions](../../README.md#development)
for commands and the CI matrix.

| File | Purpose | Why it is needed |
| --- | --- | --- |
| [`harness.py`](harness.py) | Creates isolated installations, selects the platform's script and shell, and exposes test inputs and results. | Lets the same Python tests exercise Bash and PowerShell with equivalent arguments. |
| [`stub.py`](stub.py) | Implements fake Docker, web, and host commands in Python; records calls and maintains fake container state. | Keeps fixture behavior reviewable in Python and avoids contacting real services or changing the host. |
| [`run.ps1`](run.ps1) | Binds Python's inputs to PowerShell commands and exports requested Windows state. | Python cannot replace cmdlets or inspect .NET objects inside a separate PowerShell process. This bridge preserves that coverage without adding test hooks to production scripts. |

## `harness.py`: setup and invocation

`Sandbox(container_os='linux', *, observe_proxy=False, observe_acl=False)` creates
a temporary installation containing the checked-out manager, an `abc123` agent
config, and a version file pinned to `1.2.9`. Paths deliberately contain spaces;
Windows paths are resolved to avoid mismatches between short and long names.
Register `sandbox.close` with `self.addCleanup` so files are removed on failure.

The actual host selects `.sh` or `.ps1`. `container_os` only controls the fake
Docker daemon's container mode; it does not select the host platform. Windows
tests exercise both Linux-container and Windows-container modes.

| API | Inputs and result |
| --- | --- |
| `run(*arguments, install=False, parameters=None)` | Runs the copied manager, or the repository's installer when `install=True`. Returns `subprocess.CompletedProcess` with `returncode` and merged stdout/stderr in `stdout`. |
| `install(**parameters)` | Runs the installer with `ConfigPath=sandbox.config` and `InstallDir=sandbox.installed` unless overridden. Translates these to Bash's positional config argument and `--install-dir`. An empty `ConfigPath` selects the TOKEN flow. |
| `settings(**values)` | Writes the platform's settings file; for example, `MEMORY_ALLOCATION_MB=2048` or Windows-only `WINDOWS_VERSION='ltsc2025'`. Returns the file path. |
| `calls(command)` | Returns recorded argument arrays for a command. Common names are `docker`, `request`, `drive`, and `manager`. |
| `image` | Returns the fake running container's image, or `None` when stopped. |
| `snapshot` | Reads Windows observations. Returns `{}` when neither observer is enabled; unavailable on Bash. |
| `env` | Environment mapping passed to the child. Set production inputs here, such as `HTTPS_PROXY`, `TOKEN`, or `PROXY_AGENT_REGISTRY_TAGS_URL`. |

`invoke(script, arguments=(), *, parameters=None, env=None, timeout=30)` is the
lower-level subprocess helper, also used by integration tests with real Docker.
It maps `InstallDir` to `--install-dir` on Bash and named parameters on PowerShell.
Its default timeout is 30 seconds; callers can override it. Windows children run
without profiles or interactive prompts, with execution policy bypassed. The
inherited `PSModulePath` is removed so each PowerShell version finds its own
modules, including those needed for ACL inspection.

Set `PROXY_AGENT_TEST_SHELL` **before starting Python** to select the executable.
The default is `bash` on Unix and `powershell` on Windows; use `pwsh` for PowerShell
7 or an executable path to test a specific Bash version. The sandbox clears
inherited proxy variables and installer overrides, then initializes
`RUNTIME=docker` and `MEMORY_ALLOCATION_MB=5120`. Modify `sandbox.env` to test other
values without changing the parent process's environment.

### Fixture options

Change `sandbox.options` before calling `run()` or `install()`. The harness writes
the current values to `options.json` for every invocation. These options control
mock behavior; they are separate from the production environment in `sandbox.env`.

| Option | Default | Effect |
| --- | --- | --- |
| `container_os` | Constructor argument; `linux` by default | Docker's reported OS type. Use `windows` for Windows-container tests on Windows. |
| `tags` | Copy of `harness.TAGS` | Registry response containing mixed Ubuntu, Windows LTSC, and bare version tags. Replace it to test version selection or missing variants. |
| `build` | `20348` | Windows OS build reported by `Get-CimInstance`; for example, `17763` for Server 2019 or `26100` for Server 2025. |
| `product_type` | `3` | Windows OS product type. Use `1` to simulate a client host. |
| `free_mb` | `100000` | Available disk space reported by Bash's `df` or PowerShell's `Get-PSDrive`, in MiB. |
| `docker_root` | Unset | Docker's data root. When absent or empty, that lookup returns an empty result and exits 1, exercising the installer's fallback. |
| `uid` | `1000` | Bash's `id` result. Set to `0` to test rejection of root installs. |
| `unhealthy_tag` | Unset | Image substring that makes Docker health checks report `unhealthy`; other running images report `healthy`. |
| `doctor_exit` | `0` | Exit code returned by the fake doctor. |
| `doctor_report` | `stub doctor report` | Doctor stdout. The stub also emits `stub doctor log` on stderr. |
| `doctor_log` | `False` | When true, writes `agent/logs/doctor-stub.log`, simulating a doctor that saves its own report. |
| `system_proxy` | Unset | Windows-only URL used to initialize a fake .NET system proxy; otherwise the child starts with a direct proxy. |
| `system_credentials` | `False` | Windows-only switch that seeds the system proxy with fixed `proxy-user` / `proxy-password` credentials, for preservation tests. |

Other fixture responses are fixed in `stub.py`: Docker reports version `27.0.0`,
the Windows computer-system lookup reports 64 GiB RAM and eight logical CPUs, and
web configuration responses return HTTP 200 with an `abc123` agent config.
The Bash `date` stub always returns `2020-01-02T03-04-05` so repeated doctor runs
exercise filename collisions. Installer downloads produce a small launcher that
records the manager's arguments instead of starting another manager.

Authentication, actual HTTP requests, image builds, and real container behavior
are covered by the separate [integration tests](../test_integration.py).

### Windows observations

Observers default to off. Enable only the state a test needs:

| Setting | Result in `sandbox.snapshot` |
| --- | --- |
| `observe_proxy=True` | Proxy address and credentials, default-credential status, and whether the original proxy and credentials objects were retained. PowerShell 7 also reports whether `HttpClient.DefaultProxy` points to the same object. |
| `sandbox.proxy_probes = [url, ...]` | With proxy observation enabled, adds a `bypass` mapping from each URL to the result of .NET's `IsBypassed`. Defaults to an empty list. |
| `observe_acl=True` | When the installed config exists, reports ACL inheritance protection, access entries (`sid`, `rights`, `type`), the current user's SID, and administrator status. |

Both observer flags can also be changed on the sandbox before an invocation.
Snapshots are captured after execution, including when a script exception is
caught by the bridge. Each invocation uses a new process, so its proxy changes
do not affect other tests or the Python parent.

## `stub.py`: command behavior and transport

On Bash, temporary `docker`, `curl`, `id`, `df`, and `date` wrappers on `PATH`
forward arguments to `stub.py <command> [arguments...]`. On Windows, PowerShell
functions forward calls through these JSON interfaces:

| Entry point | UTF-8 JSON on stdin | Output |
| --- | --- | --- |
| `docker-json` | Docker argument array | Native stdout/stderr and exit code. |
| `manager-json` | Downloaded launcher's argument array | Records the call as `manager`; exits 0. |
| `powershell-json` | Array starting with the cmdlet name, followed by its arguments | JSON fixture response, converted to a PowerShell object by `run.ps1`. |

JSON preserves embedded quotes, empty strings, Unicode, and object arguments
such as HTTP headers across PowerShell versions. Passing them as native command
arguments would expose Windows PowerShell 5.1's quoting and encoding differences.

The supported cmdlet interfaces are deliberately limited:

| Cmdlet | Accepted parameters |
| --- | --- |
| `Invoke-RestMethod` | Positional or named `Uri`, plus `Method` and `TimeoutSec`. Returns the registry tags. |
| `Invoke-WebRequest` | Positional or named `Uri`, plus `Method`, `TimeoutSec`, `Headers`, `OutFile`, and the `UseBasicParsing` switch. Writes the launcher when `OutFile` is supplied; otherwise returns the configuration response. |
| `Get-CimInstance` | Positional or named `ClassName`; supports only `Win32_OperatingSystem` and `Win32_ComputerSystem`. |
| `Get-PSDrive` | Positional or named `Name`; returns `free_mb` converted to bytes. |

Parameter names are case insensitive. `ErrorAction` is accepted as a value-taking
parameter but does not change fixture behavior. Unknown parameters, missing
values, and unsupported host queries return error JSON and exit 1. Extend the
Python mock explicitly when production starts using a new interface; the bridge
must never fall back to real host or network calls.

## `run.ps1`: PowerShell-only glue

The only command-line parameter is mandatory `-RequestPath`, pointing to the JSON
file generated by `Sandbox.windows_request()`. Tests normally call the Python API
rather than invoking the bridge directly.

| Request field | Meaning |
| --- | --- |
| `script` | Path to the real script or a bridge-test probe. |
| `arguments` | Positional argument array. |
| `parameters` | Named parameter mapping, converted to a PowerShell hashtable. The harness moves `-Verbose` here so PowerShell consumes it rather than forwarding it to the doctor. |
| `system_proxy`, `system_credentials` | Initial .NET proxy fixtures, as described above. |
| `observe_proxy`, `proxy_probes` | Whether to export proxy state and which URLs to inspect. |
| `config_path` | Installed config path when ACL observation is requested; otherwise null. |
| `snapshot_path` | Destination for the JSON snapshot, including `{}` when no observer is enabled. |

The bridge sets UTF-8 pipe encoding explicitly for PowerShell 5.1. Cmdlet mock
failures, empty responses, and invalid JSON are test infrastructure failures:
the bridge records them and returns failure even if the production script catches
the exception. Docker is different: its nonzero exit codes are expected inputs
for tests such as failed upgrades and doctor checks, so they retain native exit
semantics. Script exceptions produce exit code 1 and their text in captured output.

The boundary is tested in [`test_harness.py`](../test_harness.py), including
quotes, empty arguments, Unicode, swallowed helper exceptions, unsupported host
queries, and observation capture after a script exception.

## Temporary files and environment

The harness manages these automatically; tests should use its API:

| File under `sandbox.root` | Purpose |
| --- | --- |
| `agent/` | Copied manager, input config, pinned version, settings, and manager/doctor logs. |
| `installed/` | Default destination for installer tests. |
| `options.json` | Current Python mock options, shared by stub subprocesses. |
| `calls.jsonl` | Recorded calls, one JSON argument array per line, starting with the command name. |
| `image` | Fake container state; present while an agent container is running. |
| `request.json` | Windows invocation and observation settings. |
| `snapshot.json` | Windows observations from the most recent invocation. |
| `bin/` | Bash command wrappers that forward to Python. |

`AGENT_TEST_ROOT` points to this directory, `AGENT_TEST_PYTHON` selects the same
interpreter running the tests, and `AGENT_TEST_STUB` points to `stub.py`. These are
internal transport settings; ordinary test scenarios should change
`sandbox.options` or `sandbox.env` instead. Bridge failure tests deliberately
replace `AGENT_TEST_STUB` to verify that broken helpers cannot look successful.
