# Fivetran Proxy Agent

The Fivetran Proxy Agent allows you to sync data sources to Fivetran from within your private network. The agent runs in your environment and communicates outbound with Fivetran — no inbound firewall rules are required. Configuration and monitoring are performed through the Fivetran dashboard or API.

For more information see the [Proxy Agent documentation](https://fivetran.com/docs/destinations/connection-options/proxy-agent).

> **Note:** You must have a valid agent TOKEN before you can start the agent. The TOKEN can be obtained when you create the agent in the Fivetran Dashboard.

---

## Requirements

### Linux
- x86_64 Linux host
- Docker 20.10.17 or later (running, accessible to your user)
- Minimum 4 CPUs, 5 GB RAM, 2 GB free disk space

### Windows
- Windows 10 or Windows 11 (64-bit) with Docker Desktop (current supported version) and the WSL2 backend for Linux containers, or Windows Server 2019, Windows Server 2022, or Windows Server 2025 (64-bit) for Windows containers
- Windows Server: Docker CE 29.8.2 (recommended) with the Containers feature enabled and Docker in Windows-container mode
- Minimum 4 CPUs, 8 GB RAM, 2 GB free disk space (12 GB free disk space on Windows Server with Windows containers)
- Windows Server: the 12 GB is used by Docker's image storage, so it must be free on the drive that holds Docker's data root (`C:\ProgramData\docker` by default, or the `data-root` set in Docker's `daemon.json`). The installer checks that drive.
- PowerShell 5.1 or later (built into Windows)

> **Note:** On Windows 10 and Windows 11, Docker Desktop requires an interactive user session to start. The agent container will not start automatically on boot until a user signs in and Docker Desktop launches.

> **Note:** Windows Server installations must use Windows-container mode. Hyper-V isolation may require nested virtualization; verify that requirement for the selected host and isolation mode.

> **Note:** On Windows Server, `localhost` inside the agent container refers to the container itself, and the host's private IP address may not be reachable from the container. To connect to a database running on the same host, use the container's gateway IP address (the host's `vEthernet (nat)` adapter address).

> **Note:** Running Docker Desktop inside a VM, including a cloud VM, requires nested virtualization for its local Linux VM backend. Verify that the VM size and hypervisor support and enable nested virtualization before installation. See [Docker's VM/VDI guidance](https://docs.docker.com/desktop/setup/vm-vdi/) for supported environments and requirements.

## Installation

### Linux

Run the following as a non-root user:

```bash
TOKEN="YOUR_AGENT_TOKEN" RUNTIME=docker bash -c "$(curl -sL https://raw.githubusercontent.com/fivetran/proxy_agent/main/install.sh)"
```

To install into a custom directory:

```bash
TOKEN="YOUR_AGENT_TOKEN" RUNTIME=docker bash -c "$(curl -sL https://raw.githubusercontent.com/fivetran/proxy_agent/main/install.sh)" -- --install-dir /path/to/dir
```

### Windows

Open PowerShell (does not need to run as Administrator) and run:

```powershell
Invoke-WebRequest -Uri https://raw.githubusercontent.com/fivetran/proxy_agent/main/install.ps1 -OutFile install.ps1 -UseBasicParsing
Unblock-File .\install.ps1
$env:RUNTIME = 'docker'; $env:TOKEN = 'YOUR_AGENT_TOKEN'; & .\install.ps1
```

To install into a custom directory:

```powershell
$env:RUNTIME = 'docker'; $env:TOKEN = 'YOUR_AGENT_TOKEN'; & .\install.ps1 -InstallDir C:\path\to\dir
```

The installer pins the plain agent version (for example `1.2.9`) in the `version` file, and the management script appends the image tag suffix for the Docker container mode when it starts the container. Linux-container mode, including Windows 10/11 with Docker Desktop and WSL2, uses the Ubuntu image tag `<version>-ubuntu-26.04`. Windows-container mode detects the Windows Server LTSC version and selects the matching `<version>-windows-<ltsc>` image tag. Existing installations whose `version` file contains a full image tag keep working; the file is rewritten to the plain version on the next `start` or `upgrade`. To override detection for a host known to support a specific Windows image, pass `-WindowsVersion ltsc2019`, `-WindowsVersion ltsc2022`, or `-WindowsVersion ltsc2025`.

Windows Server hosts use an exact LTSC mapping: Server 2019 selects `ltsc2019`, Server 2022 selects `ltsc2022`, and Server 2025 selects `ltsc2025`. The `-WindowsVersion` option is an operator-controlled override and does not validate host/image compatibility; confirm the supported Windows container version and required isolation mode before using it. The override is saved as `$WINDOWS_VERSION` in `settings.ps1`, so `upgrade` keeps using it.

Linux releases continue to use `proxy-agent:<version>` for backward compatibility and are also published as `proxy-agent:<version>-ubuntu-26.04`. Both tags reference the same Linux multi-architecture image. Windows releases use the explicit `proxy-agent:<version>-windows-ltsc2019`, `proxy-agent:<version>-windows-ltsc2022`, or `proxy-agent:<version>-windows-ltsc2025` tags.

### Installing behind a network proxy

The installers and the `upgrade` command use a proxy for their web requests: downloading the management script, listing image tags, and fetching the agent configuration from Fivetran.

On Linux, the shell scripts use `curl`. Set `HTTPS_PROXY` or `https_proxy` for HTTPS URLs, which the scripts use by default. Set lowercase `http_proxy` for HTTP URLs; curl ignores uppercase `HTTP_PROXY`. `NO_PROXY` or `no_proxy` excludes hosts.

On Windows, the PowerShell scripts use `Invoke-WebRequest` and `Invoke-RestMethod`. They apply `HTTPS_PROXY` (or `HTTP_PROXY` as a fallback) and `NO_PROXY` themselves because Windows PowerShell 5.1 does not read these variables for web requests. The proxy URL may include credentials: `http://user:pass@proxy.example.com:3128`.

```powershell
$env:HTTPS_PROXY = 'http://proxy.example.com:3128'
$env:NO_PROXY    = 'localhost,127.0.0.1,.example.com'
$env:RUNTIME = 'docker'; $env:TOKEN = 'YOUR_AGENT_TOKEN'; & .\install.ps1
```

On Windows, the variables take precedence over the proxy in Internet Options; without them, the scripts use the Internet Options proxy as any PowerShell web request would, and print it when one is in effect. In both cases a proxy that asks for NTLM or Kerberos authentication is answered with the current Windows identity, unless the URL carries its own credentials.

On Windows, download `install.ps1` first, as shown above, rather than fetching it inline with `Invoke-RestMethod`: the download itself runs before the script can apply the proxy settings.

These variables configure the installer and management scripts, not Docker. Configure [Docker's daemon proxy](https://docs.docker.com/engine/daemon/proxy/) for image pulls. For the running agent on either platform, set `proxy_host` and `proxy_port` in `config.json` as described in the [Linux](https://fivetran.com/docs/destinations/connection-options/proxy-agent/setup-guide-docker#optionalconfigureproxyagenttouseproxyserver) and [Windows](https://fivetran.com/docs/destinations/connection-options/proxy-agent/setup-guide-docker#optionalconfigureproxyagentonwindowstouseproxyserver) setup guides. Docker client proxy settings containing credentials are stored in the container's environment and can be seen with `docker inspect`.

The installer will:
- Check prerequisites
- Create the installation directory
- Download the management script
- Fetch your agent configuration from Fivetran
- Start the agent container

Installation directory structure:

```
# Linux
$HOME/fivetran-proxy-agent/
├── proxy-agent-manager.sh   --> Management script
├── config/
│   └── config.json          --> Agent configuration (permissions: 600)
├── logs/                    --> Agent, manager and doctor logs
└── version                  --> Pinned agent version (for example 1.2.9)

# Windows
%USERPROFILE%\fivetran-proxy-agent\
├── proxy-agent-manager.ps1  --> Management script
├── config\
│   └── config.json          --> Agent configuration (owner read/write; SYSTEM and Administrators read)
├── logs\                    --> Agent, manager and doctor logs
└── version                  --> Pinned agent version (for example 1.2.9)
```

## Managing the agent

### Linux

Use `proxy-agent-manager.sh` to control the agent:

```bash
./proxy-agent-manager.sh {start|stop|restart|upgrade|status|logs|doctor [options]}
```

### Windows

Use `proxy-agent-manager.ps1` to control the agent:

```powershell
& "$env:USERPROFILE\fivetran-proxy-agent\proxy-agent-manager.ps1" {start|stop|restart|upgrade|status|logs|doctor [options]}
```

| Command   | Description                                          |
|-----------|------------------------------------------------------|
| `start`   | Start the agent container                            |
| `stop`    | Stop and remove the agent container                  |
| `restart` | Stop then start the agent container                  |
| `upgrade` | Pull and start the latest version, with auto-rollback on failure |
| `status`  | Show container name, image, and health status        |
| `logs`    | Stream live container logs                           |
| `doctor`  | Check the installation and connectivity to Fivetran in a separate container. The doctor's log and archive are saved to `logs/`, or its output if the doctor saves no log, as in 1.2.10. Options are passed to the agent's `--doctor`, e.g. `doctor --region us-east-1` |

## Troubleshooting

### Windows

**Execution policy error** — If PowerShell blocks the script with `running scripts is disabled`, your execution policy is set to Restricted. Allow local scripts and retry:
```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
```

**Docker Desktop not starting on boot** — Docker Desktop launches when you sign in, not at system boot. The agent container will not be available until a user signs in.

**Docker daemon not accessible** — ensure Docker Desktop is running before running the installer or management script.

**Volume mount issues** — Docker Desktop for Windows translates Windows paths in volume mounts automatically. If you see an empty config inside the container, ensure the install directory path does not contain special characters.

## Development

CI (`.github/workflows/ci.yml`) runs on every pull request:

- A dependency audit rejects known vulnerabilities in the locked Python test dependencies.
- `shellcheck` on the shell scripts and PSScriptAnalyzer (settings in `PSScriptAnalyzerSettings.psd1`) on the PowerShell scripts.
- A shared Python `pytest` suite for both installers and management scripts (`tests/test_*.py`). It detects the host platform and invokes Bash or PowerShell with equivalent arguments. Most cases stub external commands; proxy contracts use real curl or PowerShell web requests against local HTTP servers. Unit tests need no Docker daemon or external network. Common assertions also run in both Linux-container and Windows-container modes on Windows. Explicit Windows expectations cover LTSC selection, container paths and health checks, config ACLs, and .NET proxy handling; Linux checks mode 600 and rejection of root installs.
- The unit matrix covers Bash 3.2 on macOS, Bash on Ubuntu 22.04 and the current Ubuntu runner, and Windows PowerShell 5.1 and PowerShell 7 on Windows Server 2022 and 2025. `PROXY_AGENT_TEST_SHELL` selects the executable under test; it defaults to `bash` on Unix and `powershell` on Windows.
- Shared Docker integration tests (`tests/test_integration.py`) run on Ubuntu (amd64 and arm64), and Windows Server 2022 and 2025 with Windows containers under both PowerShell 5.1 and 7. They build a stub agent image (`tests/stub/`) and use a local HTTP server (`tests/integration/registry_server.py`) for the registry, script download, and configure endpoint. They cover config-file, TOKEN, and legacy WebSocket installation, invalid credentials, version rollback, upgrade, doctor, restart and stop.

Fixtures and mock behavior live in `tests/support/stub.py`; invocation and fixture setup live in `tests/support/harness.py`. `tests/support/win_run.py` uses pythonnet to host the selected PowerShell engine in a disposable Python process, binding arguments as .NET values and reading native proxy or ACL observations. PowerShell 5.1 uses .NET Framework; PowerShell 7 uses its bundled CoreCLR. Mock commands use UTF-8 JSON over stdin to preserve quotes, empty strings, and Unicode under both PowerShell versions. Python tracks failed mocks independently, so they fail the test even if the script catches their exceptions.

Use Python 3.11 or later and install the test dependency from `tests/requirements.txt`. Unix proxy tests also require curl. Run from the repository root:

```bash
python3 -m pip install -r tests/requirements.txt
python3 -m pytest -v
PROXY_AGENT_TEST_SHELL=/bin/bash python3 -m pytest -v
PROXY_AGENT_INTEGRATION=1 python3 -m pytest tests/test_integration.py -v
```

```powershell
python -m pip install -r tests/requirements.txt
python -m pytest -v
$env:PROXY_AGENT_TEST_SHELL = 'pwsh' # defaults to Windows PowerShell 5.1
python -m pytest -v
$env:PROXY_AGENT_INTEGRATION = '1'
python -m pytest tests/test_integration.py -v
```

Integration tests need a running Docker daemon (Windows-container mode on Windows Server) and network access to pull the stub's `alpine` or `servercore` base image. Run Linux integration tests as a regular user, as required by the installer. They use the fixed fixture container name `proxy-agent-test-agent`, so run one integration suite at a time per Docker daemon. Unit tests use isolated temporary directories, including paths with spaces.

The scripts read these environment variables so the tests can point them at the stub image and local server. They are not meant for production use:

| Variable | Read by | Replaces |
|----------|---------|----------|
| `PROXY_AGENT_IMAGE` | management scripts | the agent image repository |
| `PROXY_AGENT_REGISTRY_TAGS_URL` | installers and management scripts | the registry tag listing URL |
| `PROXY_AGENT_SCRIPT_URL` | installers | the management script download URL |

## License

This project is licensed under the [Apache License 2.0](./LICENSE).
