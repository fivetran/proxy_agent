# Native Proxy Agent manager

The Java host CLI currently implements `manager install`. It checks
prerequisites, prepares the installation directory, configures the agent, and
pins the compatible image version. It installs and invokes the existing
platform manager for `start`. The legacy manager continues to handle the other
management commands.

The Java sources live under `src/com/fivetran/proxy_agent`. The Windows
adapter in `resources` preserves native proxy credentials, CIM queries, and
config ACLs during the migration. Linux installation uses the existing curl and
Docker prerequisites.

## Build

Install Bazelisk and a GraalVM JDK with Native Image for the build host. On
Windows, also install MSVC and the Windows SDK. CI builds Linux x86-64 and
AArch64 binaries and a Windows x86-64 binary with GraalVM Community Edition
25.6.5.1. Bazel compiles the sources for Java 17, matching the engineering
repository. The CLI uses Picocli's programmatic model, which does not require
reflection configuration for the commands and options.

```bash
bazel build //proxy_agent:manager_deploy.jar
mkdir -p dist
native-image -jar bazel-bin/proxy_agent/manager_deploy.jar dist/manager
```

On Windows, create `dist` and run the equivalent commands in PowerShell.
Native Image produces `dist\manager.exe`. Customer hosts can run the native
binary directly without Java installed. [Native Image build prerequisites](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites)

## Install with the native manager

Run `manager install [config.json] [--install-dir <dir>]` with
`RUNTIME=docker`. Set `TOKEN` when installing via the configuration endpoint.
Windows also accepts `--windows-version ltsc2019|ltsc2022|ltsc2025` in
Windows-container mode.

By default both bootstrap scripts fetch the binary and SHA-256 digest for the
version named in the script. They also accept `PROXY_AGENT_MANAGER_BIN` for a
local native binary. For a different hosted binary, set
`PROXY_AGENT_MANAGER_BINARY_URL` and
`PROXY_AGENT_MANAGER_BINARY_SHA256` together. The bootstrapper verifies the
download before invoking `manager install`. Use a digest from a trusted
release source. Java creates the install directories and stages a copy of the
native binary before placing it beside the legacy manager. The bootstrapper
can remove its temporary download after the install process exits.

## First release

Before merging the bootstrap cutover, publish the binaries and their separate
`.sha256` files from a green CI run as release assets under `manager-v0.1.0`.
Verify the default download path from both bootstrap scripts against that
release. This lets the public installer URLs switch over in the same code PR.

The Python tests remain in place. Installer unit tests use the Bazel-built JAR
with the existing dependency stubs. CI exercises the native executable through
the existing Docker integration suite.
