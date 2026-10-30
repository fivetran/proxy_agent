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
- Minimum 4 CPUs, 5 GB RAM, 2 GB free disk space
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

The installer detects the Docker container mode. Linux-container mode, including Windows 10/11 with Docker Desktop and WSL2, uses the explicit Ubuntu image tag `<version>-ubuntu-26.04`. Existing Linux installations that pin the legacy numeric tag `<version>` continue to work and are upgraded using the explicit Ubuntu tag. Windows-container mode detects the Windows Server LTSC version and selects the matching image tag. To override detection for a host known to support a specific Windows image, pass `-WindowsVersion ltsc2019`, `-WindowsVersion ltsc2022`, or `-WindowsVersion ltsc2025`.

Windows Server hosts use an exact LTSC mapping: Server 2019 selects `ltsc2019`, Server 2022 selects `ltsc2022`, and Server 2025 selects `ltsc2025`. The `-WindowsVersion` option is an operator-controlled override and does not validate host/image compatibility; confirm the supported Windows container version and required isolation mode before using it.

Linux releases continue to use `proxy-agent:<version>` for backward compatibility and are also published as `proxy-agent:<version>-ubuntu-26.04`. Both tags reference the same Linux multi-architecture image. Windows releases use the explicit `proxy-agent:<version>-windows-ltsc2019`, `proxy-agent:<version>-windows-ltsc2022`, or `proxy-agent:<version>-windows-ltsc2025` tags.

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
├── logs/                    --> Agent and manager logs
└── version                  --> Pinned agent image tag

# Windows
%USERPROFILE%\fivetran-proxy-agent\
├── proxy-agent-manager.ps1  --> Management script
├── config\
│   └── config.json          --> Agent configuration (owner read/write; SYSTEM and Administrators read)
├── logs\                    --> Agent and manager logs
└── version                  --> Pinned agent image tag
```

## Managing the agent

### Linux

Use `proxy-agent-manager.sh` to control the agent:

```bash
./proxy-agent-manager.sh {start|stop|restart|upgrade|status|logs}
```

### Windows

Use `proxy-agent-manager.ps1` to control the agent:

```powershell
& "$env:USERPROFILE\fivetran-proxy-agent\proxy-agent-manager.ps1" {start|stop|restart|upgrade|status|logs}
```

| Command   | Description                                          |
|-----------|------------------------------------------------------|
| `start`   | Start the agent container                            |
| `stop`    | Stop and remove the agent container                  |
| `restart` | Stop then start the agent container                  |
| `upgrade` | Pull and start the latest version, with auto-rollback on failure |
| `status`  | Show container name, image, and health status        |
| `logs`    | Stream live container logs                           |

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

- `shellcheck` on the shell scripts and PSScriptAnalyzer (settings in `PSScriptAnalyzerSettings.psd1`) on the PowerShell scripts.
- Unit tests for the management scripts: `tests/bash/manager.bats` (bats) and `tests/pwsh/Manager.Tests.ps1` (Pester 5 under Windows PowerShell 5.1). Both run the script with stubbed `docker` and registry calls, so they need no Docker daemon.
- Integration tests that run the installer and management script against a real Docker daemon: `tests/integration/linux.sh` on Ubuntu, and `tests/integration/windows.ps1` on Windows Server 2022 and 2025 with Windows containers. They use a stub agent image (`tests/stub/`) that only writes the heartbeat file, and a local stand-in for the registry, the script download and the configure endpoint (`tests/integration/registry_server.py`), so they need no Fivetran credentials. They cover installing from a config file, from a `TOKEN`, and from a legacy WebSocket config, then rollback, upgrade, restart and stop.

To run them locally you need Docker for the integration tests, `python3` (`python` on Windows) for the registry stand-in, [bats-core](https://github.com/bats-core/bats-core) for the bash tests and [Pester 5](https://pester.dev) for the PowerShell tests. The stub images pull `alpine` and `servercore` base images, so the integration tests need network access for that.

```bash
bats tests/bash
tests/integration/linux.sh            # needs Docker
```

```powershell
Invoke-Pester -Path tests/pwsh
tests\integration\windows.ps1          # needs Docker in Windows-containers mode
```

The scripts read these environment variables so the tests can point them at the stub image and local server. They are not meant for production use:

| Variable | Read by | Replaces |
|----------|---------|----------|
| `PROXY_AGENT_IMAGE` | management scripts | the agent image repository |
| `PROXY_AGENT_REGISTRY_TAGS_URL` | installers and management scripts | the registry tag listing URL |
| `PROXY_AGENT_SCRIPT_URL` | installers | the management script download URL |

## License

This project is licensed under the [Apache License 2.0](./LICENSE).
