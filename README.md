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
- Windows 10 or Windows 11 (64-bit)
- Docker Desktop (current supported version) with WSL2 backend
- Minimum 4 CPUs, 5 GB RAM, 2 GB free disk space
- PowerShell 5.1 or later (built into Windows)

> **Note:** Docker Desktop requires an interactive user session to start. The agent container will not start automatically on boot until a user signs in and Docker Desktop launches.

> **Note:** Docker Desktop requires nested virtualization, which is not available on all cloud VM types. Check that your instance supports it (AWS Nitro-based metal instances, GCP VMs with nested virtualization enabled, Azure Dv3/Ev3 and later). Standard general-purpose cloud VMs often do not expose it.

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
└── version                  --> Pinned agent version

# Windows
%USERPROFILE%\fivetran-proxy-agent\
├── proxy-agent-manager.ps1  --> Management script
├── config\
│   └── config.json          --> Agent configuration (owner read/write only)
├── logs\                    --> Agent and manager logs
└── version                  --> Pinned agent version
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

## License

This project is licensed under the [Apache License 2.0](./LICENSE).
