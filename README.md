# Fivetran Proxy Agent

The Fivetran Proxy Agent allows you to sync data sources to Fivetran from within your private network. The agent runs in your environment and communicates outbound with Fivetran — no inbound firewall rules are required. Configuration and monitoring are performed through the Fivetran dashboard or API.

For more information see the [Proxy Agent documentation](https://fivetran.com/docs/destinations/connection-options/proxy-agent).

> **Note:** You must have a valid agent TOKEN before you can start the agent. The TOKEN can be obtained when you create the agent in the Fivetran Dashboard.

---

## Requirements

- x86_64 Linux host
- Docker 20.10.17 or later (running, accessible to your user)
- Minimum 2 CPUs, 2 GB RAM, 5 GB free disk space

## Installation

Run the following as a non-root user:

```bash
TOKEN="YOUR_AGENT_TOKEN" RUNTIME=docker bash -c "$(curl -sL https://raw.githubusercontent.com/fivetran/proxy_agent/main/install.sh)"
```

To install into a custom directory:

```bash
TOKEN="YOUR_AGENT_TOKEN" RUNTIME=docker bash -c "$(curl -sL https://raw.githubusercontent.com/fivetran/proxy_agent/main/install.sh)" -- --install-dir /path/to/dir
```

The installer will:
- Check prerequisites
- Create the installation directory
- Download the management script
- Fetch your agent configuration from Fivetran
- Start the agent container

Installation directory structure:

```
$HOME/fivetran-proxy-agent/
├── proxy-agent-manager.sh   --> Management script
├── config/
│   └── config.json          --> Agent configuration (permissions: 600)
├── logs/                    --> Agent and manager logs
└── version                  --> Pinned agent version
```

## Managing the agent

Use `proxy-agent-manager.sh` to control the agent:

```bash
./proxy-agent-manager.sh {start|stop|restart|upgrade|status|logs}
```

| Command   | Description                                          |
|-----------|------------------------------------------------------|
| `start`   | Start the agent container                            |
| `stop`    | Stop and remove the agent container                  |
| `restart` | Stop then start the agent container                  |
| `upgrade` | Pull and start the latest version, with auto-rollback on failure |
| `status`  | Show container name, image, and health status        |
| `logs`    | Stream live container logs                           |

## Third-party licenses

See [LICENSE.html](./LICENSE.html) for third-party dependency license information.
