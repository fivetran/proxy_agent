# Native Proxy Agent manager

The Java host CLI currently implements `manager install`. It checks
prerequisites, prepares the installation directory, configures the agent, and
pins the compatible image version. It installs and invokes the existing
platform manager for `start`. The legacy manager handles the other commands.

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
