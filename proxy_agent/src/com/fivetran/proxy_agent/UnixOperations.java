package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;

/** Host operations shared by Linux and macOS. */
final class UnixOperations implements Operations {
    private static final Pattern LINUX_MEMORY = Pattern.compile("(?m)^MemTotal:\\s+([0-9]+)");
    private static final int BYTES_PER_MB = 1024 * 1024;

    private final InstallHost host = new InstallHost();
    private final boolean isMac = System.getProperty("os.name").startsWith("Mac");

    @Override
    public boolean isWindows() {
        return false;
    }

    @Override
    public void checkUser() throws IOException, InterruptedException {
        var userId = host.capture("id", "-u");
        if (!userId.succeeded()) {
            throw new IOException("Unable to determine the current user.");
        }
        if (userId.output().strip().equals("0")) {
            throw new IOException("This script should not be run as root. Please run as a regular user.");
        }
    }

    @Override
    public void checkAutoStart(List<String> warnings) throws IOException, InterruptedException {
        if (isMac || !host.docker("info", "--format", "{{.SecurityOptions}}").output().contains("rootless")) {
            return;
        }
        if (!host.hasCommand("loginctl", false)) {
            warnings.add("Rootless Docker detected but loginctl is not available. Ensure the agent starts on boot.");
            return;
        }
        var user = InstallHost.env("USER", System.getProperty("user.name"));
        if (!host.capture("loginctl", "show-user", user, "-p", "Linger", "--value").output().strip().equals("yes")) {
            warnings.add("Rootless Docker detected but user linger is not enabled. Run 'sudo loginctl enable-linger "
                    + user + "' to ensure the agent starts automatically on system boot.");
        }
    }

    @Override
    public int cpuCount() throws IOException {
        var cpuInfo = Path.of("/proc/cpuinfo");
        if (!Files.isReadable(cpuInfo)) {
            return Runtime.getRuntime().availableProcessors();
        }
        var count = 0;
        for (var line : Files.readAllLines(cpuInfo)) {
            if (line.startsWith("processor")) {
                count++;
            }
        }
        return count;
    }

    @Override
    public long totalMemoryMb() throws IOException, InterruptedException {
        if (isMac) {
            var result = host.capture("sysctl", "-n", "hw.memsize");
            return result.succeeded() ? InstallHost.parseSize(result.output().strip(), 0) / BYTES_PER_MB : 0;
        }
        var memInfo = Path.of("/proc/meminfo");
        if (Files.isReadable(memInfo)) {
            var matcher = LINUX_MEMORY.matcher(Files.readString(memInfo));
            if (matcher.find()) {
                return InstallHost.parseSize(matcher.group(1), 0) / 1024;
            }
        }
        return 0;
    }

    @Override
    public String windowsImageVariant() {
        throw new UnsupportedOperationException("Windows containers require a Windows host");
    }

    @Override
    public String legacyCredential(Path path, String config) throws IOException {
        return readConfigString(config, "agent_id") + ":" + readConfigString(config, "auth_token");
    }

    // Match the credential format accepted by the legacy Unix installer.
    private static String readConfigString(String config, String key) throws IOException {
        var matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(config);
        if (!matcher.find()) {
            throw new IOException("Could not extract credentials from WebSocket config");
        }
        return matcher.group(1);
    }

    @Override
    public Path createProtectedConfig(Path directory) throws IOException {
        return Files.createTempFile(directory, "config.", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    @Override
    public void makeExecutable(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    @Override
    public void start(Path manager) throws IOException, InterruptedException {
        host.start(manager, List.of(manager.toString(), "start"));
    }
}
