package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Windows host operations using PowerShell for Windows system APIs. */
class WindowsOperations implements OsOperations {
    private final CommandRunner commands;
    private final Docker docker;
    private final String powerShell = Environment.get("PROXY_AGENT_INSTALLER_POWERSHELL", "powershell.exe");

    WindowsOperations(CommandRunner commands, Docker docker) {
        this.commands = commands;
        this.docker = docker;
    }

    @Override
    public ProxySettings proxySettings() {
        return ProxySettings.forWindows();
    }

    @Override
    public Path defaultInstallDirectory() {
        return Path.of(Environment.get("USERPROFILE", System.getProperty("user.home")), "fivetran-proxy-agent");
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
    public String diskLocation(Path target) {
        return " on drive " + target.getRoot().toString().substring(0, 2) + " ";
    }

    @Override
    public boolean hasCommand(String name) throws IOException, InterruptedException {
        return commands.capture("where.exe", "/q", name + ".exe").succeeded();
    }

    @Override
    public void checkUser() {
        // Windows permits administrators. The host probe reports a warning.
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
    public long totalMemoryMb() throws IOException, InterruptedException {
        var result = runPowerShell("""
                $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
                $principal = [Security.Principal.WindowsPrincipal]::new($identity)
                [Console]::WriteLine($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
                try {
                    [Console]::WriteLine([math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1MB))
                } catch {
                    # The installer reports unavailable host memory while continuing the preflight checks.
                }
                """, "");
        var lines = result.output().strip().split("\\R");
        if (lines[0].equalsIgnoreCase("true")) {
            System.out.println("WARNING: Running as Administrator is not recommended. Consider running as a regular user.");
        }
        try {
            return lines.length > 1 ? Long.parseLong(lines[1]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
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
