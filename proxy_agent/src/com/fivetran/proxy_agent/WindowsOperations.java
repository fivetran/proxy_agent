package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Windows host operations using PowerShell for native authentication and system APIs. */
final class WindowsOperations implements Operations {
    private static final String POWERSHELL_SETUP = "/com/fivetran/proxy_agent/windows-installer.ps1";
    private final InstallHost host = new InstallHost();
    private final String powerShell = InstallHost.env("PROXY_AGENT_INSTALLER_POWERSHELL", "powershell.exe");

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
                """, "", "", false);
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
                """, "", "", false);
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
                """, path.toAbsolutePath().toString(), "", false);
        if (!result.succeeded()) {
            throw new IOException("Could not extract credentials from WebSocket config");
        }
        return result.output();
    }

    @Override
    public void download(String url, Path target) throws IOException, InterruptedException {
        var result = runPowerShell("""
                Invoke-WebRequest -Uri $argument -OutFile $value -TimeoutSec 30 -UseBasicParsing
                Unblock-File -LiteralPath $value
                """, url, target.toString(), true);
        if (!result.succeeded()) {
            throw new IOException("Failed to download management script");
        }
    }

    @Override
    public List<String> getTags(String url) throws IOException, InterruptedException {
        var result = runPowerShell("""
                $response = Invoke-RestMethod -Uri $argument -Method Get -TimeoutSec 30
                foreach ($tag in $response.tags) { [Console]::WriteLine($tag) }
                """, url, "", true);
        if (!result.succeeded()) {
            throw new IOException("Unable to query image registry for latest version");
        }
        return result.output().lines().toList();
    }

    @Override
    public byte[] configure(String url, String token) throws IOException, InterruptedException {
        var result = runPowerShell("""
                try {
                    $response = Invoke-WebRequest -Uri $argument -Method Post -TimeoutSec 30 -UseBasicParsing `
                        -Headers @{ Authorization = "Basic $value"; Accept = 'application/json' }
                } catch {
                    if (-not $_.Exception.Response) { exit 1 }
                    [Console]::WriteLine([int]$_.Exception.Response.StatusCode)
                    exit 0
                }
                try {
                    $null = $response.Content | ConvertFrom-Json
                } catch {
                    exit 2
                }
                [Console]::WriteLine($response.StatusCode)
                [Console]::Write($response.Content)
                """, url, token, true);
        if (result.status() == 2) {
            throw new IOException("Configure endpoint returned an invalid JSON response");
        }
        var endOfStatus = result.output().indexOf('\n');
        if (!result.succeeded() || endOfStatus < 0) {
            throw new IOException("Failed to connect to configure endpoint");
        }
        var status = result.output().substring(0, endOfStatus).strip();
        if (status.equals("401")) {
            throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
        }
        return InstallHost.configBody(status, result.output().substring(endOfStatus + 1));
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
                """, temp.toString(), "", false);
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

    private InstallHost.Result runPowerShell(String command, String argument, String value, boolean usesProxy)
            throws IOException, InterruptedException {
        var setup = Objects.requireNonNull(WindowsOperations.class.getResourceAsStream(POWERSHELL_SETUP));
        String script;
        try (setup) {
            script = new String(setup.readAllBytes(), UTF_8);
        }
        // Keep paths and credentials as data so PowerShell never parses them as code.
        script += """
                $argument = $env:PROXY_AGENT_INSTALL_ARGUMENT
                $value = $env:PROXY_AGENT_INSTALL_VALUE
                try {
                """;
        if (usesProxy) {
            script += "Set-WebProxyFromEnvironment -ProbeUri ([Uri]$argument)\n";
        }
        script += command + "\n} catch { exit 1 }\n";
        var encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
        var process = InstallHost.builder(List.of(powerShell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        process.environment().put("PROXY_AGENT_INSTALL_ARGUMENT", argument);
        process.environment().put("PROXY_AGENT_INSTALL_VALUE", value);
        if (usesProxy) {
            // The proxy setup logs only the proxy address, with credentials removed.
            process.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        return InstallHost.capture(process, null);
    }
}
