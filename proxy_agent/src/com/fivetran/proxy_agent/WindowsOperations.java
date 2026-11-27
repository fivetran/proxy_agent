package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Windows host operations using PowerShell for Windows system APIs. */
class WindowsOperations implements OsOperations {
    private final CommandRunner commands;
    private final Docker docker;
    private final String powerShell = SystemEnvironment.get("PROXY_AGENT_INSTALLER_POWERSHELL", "powershell.exe");

    WindowsOperations(CommandRunner commands, Docker docker) {
        this.commands = commands;
        this.docker = docker;
    }

    @Override
    public void configureProxy() {
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
        var proxy = SystemEnvironment.get("HTTPS_PROXY", SystemEnvironment.get("HTTP_PROXY", ""));
        ProxySettings.configure(proxy, proxy, SystemEnvironment.get("NO_PROXY", ""));
    }

    @Override
    public Path defaultInstallDirectory() {
        return Path.of(SystemEnvironment.get("USERPROFILE", System.getProperty("user.home")), "fivetran-proxy-agent");
    }

    @Override
    public String managerScriptName() {
        return "proxy-agent-manager.ps1";
    }

    @Override
    public String managerBinaryName() {
        return "manager.exe";
    }

    @Override
    public String managerReleaseAsset() throws IOException {
        if (!System.getProperty("os.arch").equals("amd64")) {
            throw new IOException("No native manager is available for this host architecture.");
        }
        return "manager-windows-x64.exe";
    }

    @Override
    public boolean publishManagerUpdate(Path directory, Path staging) throws IOException {
        var binary = directory.resolve(managerBinaryName());
        var current = ProcessHandle.current();
        var command = current.info().command();
        if (command.isEmpty() || !Files.exists(binary) || !Files.isSameFile(Path.of(command.orElseThrow()), binary)) {
            return OsOperations.super.publishManagerUpdate(directory, staging);
        }
        // Windows holds the current executable open until this process exits.
        var log = Files.createDirectories(directory.resolve("logs")).resolve("manager-update.log");
        var script = """
                $ErrorActionPreference = 'Stop'
                $stage = $env:PROXY_AGENT_UPDATE_STAGE
                $target = $env:PROXY_AGENT_UPDATE_TARGET
                try {
                    $manager = Get-Process -Id $env:PROXY_AGENT_UPDATE_PID -ErrorAction SilentlyContinue
                    if ($manager) { Wait-Process -InputObject $manager -Timeout 60 }
                    $source = Join-Path $stage 'manager.exe'
                    $destination = Join-Path $target 'manager.exe'
                    if ([IO.File]::Exists($destination)) {
                        [IO.File]::Replace($source, $destination, [NullString]::Value)
                    } else {
                        [IO.File]::Move($source, $destination)
                    }
                    Write-Output 'Native manager updated.'
                } catch {
                    Write-Output 'Could not install the native manager update.'
                    exit 1
                } finally {
                    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
                }
                """;
        var encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
        var process = commands.builder(List.of(powerShell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        process.environment().put("PROXY_AGENT_UPDATE_STAGE", staging.toString());
        process.environment().put("PROXY_AGENT_UPDATE_TARGET", directory.toString());
        process.environment().put("PROXY_AGENT_UPDATE_PID", Long.toString(current.pid()));
        process.redirectErrorStream(true).redirectOutput(log.toFile()).start().getOutputStream().close();
        System.out.println("Update log: " + log);
        return true;
    }

    @Override
    public long reservedMemoryMb() {
        return 3072;
    }

    @Override
    public Settings settings(int memoryMb, String windowsVersion) {
        var contents = "$MEMORY_ALLOCATION_MB = " + memoryMb + "\r\n";
        if (!windowsVersion.isEmpty()) {
            contents += "$WINDOWS_VERSION = '" + windowsVersion + "'\r\n";
        }
        return new Settings("settings.ps1", contents);
    }

    @Override
    public ContainerSettings containerSettings(Path directory) throws IOException, InterruptedException {
        var path = directory.resolve("settings.ps1");
        if (!Files.isRegularFile(path)) {
            return new ContainerSettings("5120", "");
        }
        var result = runPowerShell("""
                . $argument | Out-Null
                if ("$MEMORY_ALLOCATION_MB" -eq '') { $MEMORY_ALLOCATION_MB = 5120 }
                [Console]::WriteLine([string]$MEMORY_ALLOCATION_MB)
                [Console]::WriteLine([string]$WINDOWS_VERSION)
                """, path.toString());
        if (!result.succeeded()) {
            throw new IOException("Could not read settings from " + path);
        }
        var lines = result.output().split("\\R", -1);
        if (lines.length < 3) {
            throw new IOException("Could not read settings from " + path);
        }
        return new ContainerSettings(lines[0], lines[1]);
    }

    @Override
    public void saveWindowsVariant(Path directory, String variant) throws IOException {
        Files.writeString(directory.resolve("settings.ps1"), "\r\n$WINDOWS_VERSION = '" + variant + "'\r\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public void checkWindowsVersionOverride(String version) {}

    @Override
    public String containerOs(List<String> errors) throws IOException, InterruptedException {
        var result = docker.capture("info", "--format", "{{.OSType}}");
        var containerOs = result.output().strip();
        if (!result.succeeded() || !(containerOs.equals("linux") || containerOs.equals("windows"))) {
            errors.add("Unable to determine whether Docker is running Linux or Windows containers");
        }
        return containerOs;
    }

    @Override
    public void describeLinuxImage() {
        System.out.println("Using Linux container image");
    }

    @Override
    public @Nullable Path diskCheckTarget(Path directory) {
        return Files.exists(directory) ? directory : directory.getParent();
    }

    @Override
    public void warnMissingDiskTarget(@Nullable Path target, List<String> warnings) {
        warnings.add("Unable to determine available disk space: directory " + target + " does not exist");
    }

    @Override
    public boolean hasCommand(String name) throws IOException, InterruptedException {
        return commands.capture("where.exe", "/q", name + ".exe").succeeded();
    }

    @Override
    public void checkUser() throws IOException, InterruptedException {
        var result = runPowerShell(
                "([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()))"
                        + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)", "");
        if (result.succeeded() && result.output().strip().equalsIgnoreCase("true")) {
            System.out.println("WARNING: Running as Administrator is not recommended. Consider running as a regular user.");
        }
    }

    @Override
    public void checkAutoStart(List<String> warnings) {
        // The Linux rootless Docker checks do not apply on Windows.
    }

    @Override
    public int cpuCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    @Override
    public String windowsImageVariant() throws IOException, InterruptedException {
        var result = runPowerShell("""
                $os = Get-CimInstance Win32_OperatingSystem
                [Console]::WriteLine($os.ProductType)
                [Console]::WriteLine($os.BuildNumber)
                [Console]::WriteLine($os.Caption)
                """, "");
        var lines = result.output().strip().split("\\R", 3);
        if (!result.succeeded() || lines.length < 2) {
            throw new IOException("Unable to detect the Windows Server version");
        }
        if (!lines[0].equals("2") && !lines[0].equals("3")) {
            throw new IOException("Windows Server is required for Windows containers.");
        }
        return switch (lines[1]) {
            case "17763" -> "ltsc2019";
            case "20348" -> "ltsc2022";
            case "26100" -> "ltsc2025";
            default -> throw new IOException("Unsupported Windows Server build '" + lines[1] + "'.");
        };
    }

    @Override
    public Path createProtectedConfig(Path directory) throws IOException, InterruptedException {
        var temp = Files.createTempFile(directory, "config.", ".tmp");
        try {
            var result = runPowerShell("""
                $user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
                $acl = [Security.AccessControl.FileSecurity]::new()
                $acl.SetAccessRuleProtection($true, $false)
                $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($user, 'Read,Write', 'Allow')))
                $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule('NT AUTHORITY\\SYSTEM', 'Read', 'Allow')))
                $admins = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
                $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($admins, 'Read', 'Allow')))
                Set-Acl -LiteralPath $argument -AclObject $acl
                """, temp.toString());
            if (!result.succeeded()) {
                throw new IOException("Could not restrict access to " + directory.resolve("config.json"));
            }
            return temp;
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    @Override
    public void makeExecutable(Path path) {
        // Executable permissions on Windows are managed by the file ACL.
    }

    @Override
    public int start(Path manager) throws IOException, InterruptedException {
        return commands.run(manager.getParent(), List.of(powerShell, "-NoProfile", "-File", manager.toString(), "start"));
    }

    CommandRunner.Result runPowerShell(String command, String argument)
            throws IOException, InterruptedException {
        // Pass paths as data so PowerShell never parses them as code.
        var script = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
                $argument = $env:PROXY_AGENT_INSTALL_ARGUMENT
                try {
                """ + command + "\n} catch { exit 1 }\n";
        var encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
        var process = commands.builder(List.of(powerShell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        process.environment().put("PROXY_AGENT_INSTALL_ARGUMENT", argument);
        return commands.capture(process);
    }
}
