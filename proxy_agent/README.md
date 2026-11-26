# Native Proxy Agent manager

The Java host CLI implements `manager install` and `manager status`. Installation checks
prerequisites, prepares the installation directory, configures the agent, and
pins the compatible image version. It installs and invokes the existing
platform manager for `start`. The platform scripts delegate `status` when the
installed executable supports it and handle other commands.
It validates configuration fetched from Fivetran and stages replacements before
changing the files in an existing installation.

The Java sources live under `src/com/fivetran/proxy_agent`. The installer
uses shared Java code for installation and delegates host operations to
`UnixOperations` or `WindowsOperations`. macOS uses `UnixOperations` for
engineering tests.

The manager initializes JDK proxy settings before running a command. On Unix, set
`http_proxy` for HTTP, `https_proxy` or `HTTPS_PROXY` for HTTPS, and
`all_proxy` or `ALL_PROXY` as a fallback. On Windows, `HTTPS_PROXY`
takes precedence over `HTTP_PROXY` and applies to both destination
protocols. Without an explicit proxy, Windows uses Java's system proxy
discovery, including Internet Options and PAC.

Plain `http://` proxy URLs work with HTTP and HTTPS destinations.
`socks://`, `socks4://`, and `socks5://` use the JDK SOCKS settings.
HTTP and SOCKS5 proxy URLs can contain credentials. A Windows HTTP proxy
without explicit credentials can use the signed-in identity through the
JDK. An HTTP proxy URL with explicit credentials uses Java's Basic or
Digest authentication. An integrated-only proxy cannot use URL credentials.

An `https://` proxy URL requires TLS to the proxy itself and is unsupported.
`socks4a://` and `socks5h://` URLs require proxy-side DNS and are
unsupported. Host and domain entries in `NO_PROXY` become JDK bypass
patterns. CIDR entries are ignored. TLS certificates use the Java trust
store.

Explicit JDK proxy hosts take precedence over the installer environment
variables. Set `-Dhttp.proxyHost`, `-Dhttps.proxyHost`, or `-DsocksProxyHost`
and the corresponding ports before the `install` subcommand. The installer
also respects `-Dhttp.nonProxyHosts`, `-DsocksNonProxyHosts`,
`-Djava.net.useSystemProxies`, and `-Djavax.net.ssl.trustStore`.

## Build

CI uses Bash for the build steps, including Git Bash on Windows.

Install Bazelisk and a GraalVM JDK with Native Image for the build host. On
Windows, also install MSVC and the Windows SDK. CI builds Linux x86-64 and
AArch64 binaries and a Windows x86-64 binary with GraalVM Community Edition
25.6.5.1. Bazel compiles the sources for Java 17, matching the engineering
repository. The CLI uses Picocli annotations, with the command fields
registered for Native Image.

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

On older script-only installations, updating the management script retrieves
the latest full legacy manager from `main`. When that script runs
`update-manager`, it checks for the native manager beside it and installs the
verified release binary if it is missing. It leaves the running container,
configuration, and pinned agent version alone.

By default both bootstrap scripts fetch the binary and SHA-256 digest for the
version named in the script. They also accept `PROXY_AGENT_MANAGER_BIN` for a
local native binary. For a different hosted binary, set
`PROXY_AGENT_MANAGER_BINARY_URL` and
`PROXY_AGENT_MANAGER_BINARY_SHA256` together. The bootstrapper verifies the
download before invoking `manager install`. Use a digest from a trusted
release source. Java creates the install directories and stages a copy of the
native binary before placing it beside the legacy manager. The bootstrapper
can remove its temporary download after the install process exits.

## Releases

CI publishes a GitHub release after the checks on a commit on `main` pass.
The first release is `manager-v0.1.0`. Later releases append the full commit
SHA to that tag. Each release includes all three binaries, their `.sha256`
files, and both matching manager scripts. Older CI runs can publish their
assets without replacing a newer release as latest.

Rerun the failed workflow to retry publishing the same commit, or run
`gh workflow run ci.yml --ref main` to build, test, and release the current
commit on `main`. Verify the initial release URLs before switching the public
installers to the native manager.

Installer unit tests use a Bazel-built test JAR with controlled host probes and
local HTTP fixtures. Bootstrap tests exercise download and checksum handling
under Bash, Windows PowerShell 5.1, and PowerShell 7. CI exercises the native
executable through the Docker integration suite.
