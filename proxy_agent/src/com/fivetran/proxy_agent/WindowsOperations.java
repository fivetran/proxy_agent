package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Windows host operations using PowerShell for Windows system APIs. */
final class WindowsOperations implements Operations {
    private static final String POWERSHELL_SETUP = "/com/fivetran/proxy_agent/windows-installer.ps1";
    private final InstallHost host = new InstallHost();
    private final String powerShell = InstallHost.env("PROXY_AGENT_INSTALLER_POWERSHELL", "powershell.exe");

    @Override
    public InstallerHttp installerHttp() {
        return new InstallerHttp(InstallerProxy.forWindows());
    }

    @Override
    public boolean isWindows() {
        return true;
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
        return lines.length > 1 ? InstallHost.parseSize(lines[1], 0) : 0;
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
    public String legacyCredential(Path path, String config) throws IOException, InterruptedException {
        var result = runPowerShell("""
                $config = Get-Content -LiteralPath $argument -Raw | ConvertFrom-Json
                if (-not $config.agent_id -or -not $config.auth_token) { exit 1 }
                $credential = "$($config.agent_id):$($config.auth_token)"
                [Console]::Write($credential)
                """, path.toAbsolutePath().toString());
        if (!result.succeeded()) {
            throw new IOException("Could not extract credentials from WebSocket config");
        }
        return result.output();
    }

    @Override
    public void validateFetchedConfig(byte[] config) throws IOException, InterruptedException {
        var checked = runPowerShell("""
                [Console]::InputEncoding = [Text.Encoding]::UTF8
                $null = [Console]::In.ReadToEnd() | ConvertFrom-Json
                """, "", new String(config, UTF_8));
        if (!checked.succeeded()) {
            throw new IOException("Configure endpoint returned an invalid JSON response");
        }
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
    public void start(Path manager) throws IOException, InterruptedException {
        host.start(manager, List.of(powerShell, "-NoProfile", "-File", manager.toString(), "start"));
    }

    private InstallHost.Result runPowerShell(String command, String argument)
            throws IOException, InterruptedException {
        return runPowerShell(command, argument, null);
    }

    private InstallHost.Result runPowerShell(String command, String argument,
            @Nullable String input) throws IOException, InterruptedException {
        var setup = Objects.requireNonNull(WindowsOperations.class.getResourceAsStream(POWERSHELL_SETUP));
        String script;
        try (setup) {
            script = new String(setup.readAllBytes(), UTF_8);
        }
        // Keep paths and credentials as data so PowerShell never parses them as code.
        script += """
                $argument = $env:PROXY_AGENT_INSTALL_ARGUMENT
                try {
                """;
        script += command + "\n} catch { exit 1 }\n";
        var encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
        var process = InstallHost.builder(List.of(powerShell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        process.environment().put("PROXY_AGENT_INSTALL_ARGUMENT", argument);
        return InstallHost.capture(process, input);
    }
}
