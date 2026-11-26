# Native Proxy Agent manager

The Java host CLI currently implements `manager install`. It checks
prerequisites, prepares the installation directory, configures the agent, and
pins the compatible image version. It installs and invokes the existing
platform manager for `start`. The legacy manager handles the other commands.

The Java sources live under `src/com/fivetran/proxy_agent`. The installer
uses shared Java code for installation and delegates host operations to
`UnixOperations` or `WindowsOperations`. macOS uses `UnixOperations` for
engineering tests.

On Unix, set `http_proxy` for HTTP, `https_proxy` or `HTTPS_PROXY` for
HTTPS, and `all_proxy` or `ALL_PROXY` as a fallback. Java handles HTTP
forward proxies, including CONNECT for HTTPS destinations. HTTPS and SOCKS
proxy URLs use curl. Set `no_proxy` or `NO_PROXY` to a comma-separated
list of hosts or domain suffixes. CIDR entries use curl 7.86.0 or later.
When the default JDK trust store rejects a certificate, the installer can
retry with curl so it can use the host's trust configuration.

On Windows, set `HTTPS_PROXY` or its fallback `HTTP_PROXY` for both HTTP
and HTTPS requests. `NO_PROXY` applies when a proxy is set in the
environment. Java handles explicit HTTP proxies and uses the Windows web
client for Internet Options, integrated authentication, Windows certificate
trust, and proxy schemes other than HTTP. HTTPS and SOCKS proxy URLs depend
on the PowerShell and .NET version providing that client. A proxy URL can
contain credentials. When it omits them, Windows proxy authentication uses
the current Windows identity.

Without installer proxy environment variables, you can explicitly configure
the JDK route using `-Dhttp.proxyHost`, `-Dhttps.proxyHost`, their port
properties, and `-Dhttp.nonProxyHosts` before the `install` subcommand.

## Build

Install Bazelisk and a GraalVM JDK with Native Image for the build host. On
Windows, also install MSVC and the Windows SDK. CI builds Linux x86-64 and
AArch64 binaries and a Windows x86-64 binary with GraalVM Community Edition
25.6.5.1. Bazel compiles the sources for Java 17, matching the engineering
repository. The CLI uses Picocli's programmatic model. [Native Image build prerequisites](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites)

```bash
bazel build //proxy_agent:manager_deploy.jar
mkdir -p dist
native-image -jar bazel-bin/proxy_agent/manager_deploy.jar dist/manager
```

On Windows, create `dist` and run the equivalent commands in PowerShell.
Native Image produces `dist\manager.exe`. Hosts can run the native binary
without installing Java.

## Run

Run `manager install [config.json] [--install-dir <dir>]` with
`RUNTIME=docker`. Set `TOKEN` when installing via the configuration endpoint.
Windows also accepts `--windows-version ltsc2019|ltsc2022|ltsc2025` in
Windows-container mode.

The public Bash and PowerShell installers are still the normal entry points.
This PR makes the native CLI available for testing and release preparation.

## Release sequence

1. Build and smoke-test all three native executables in CI. Each workflow
   artifact contains the executable and a companion `.sha256` file.
2. Publish the assets from the same tested commit as a versioned GitHub
   release after reviewing native installer behavior on Linux and Windows.
3. In a follow-up PR, switch the public bootstrap scripts to download and verify
   those assets before running `manager install`. Verify the release URLs
   and checksums before merging that PR.
