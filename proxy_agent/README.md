# Native Proxy Agent manager

The Java host CLI currently implements `manager install`. It checks
prerequisites, prepares the installation directory, configures the agent, and
pins the compatible image version. It installs and invokes the existing
platform manager for `start`. The legacy manager handles the other commands.

The Java sources live under `src/com/fivetran/proxy_agent`. The installer
uses shared Java code for installation and delegates host operations to
`UnixOperations` or `WindowsOperations`. macOS uses `UnixOperations` for
engineering tests.

On Unix, the native installer uses the JDK HTTP APIs. Set `http_proxy` for
HTTP, `https_proxy` or `HTTPS_PROXY` for HTTPS, and `all_proxy` or
`ALL_PROXY` as a fallback. The proxy URL uses `http://` and may include
credentials. Set `no_proxy` or `NO_PROXY` to a comma-separated list of
hosts or domain suffixes. On Windows, Java invokes PowerShell to preserve
system proxy settings and integrated authentication, query CIM, and set
config ACLs.

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
