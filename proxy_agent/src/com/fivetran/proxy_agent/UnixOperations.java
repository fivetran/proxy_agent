package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Host operations shared by Linux and macOS. */
final class UnixOperations implements OsOperations {
    private final CommandRunner commands;
    private final boolean isMac = System.getProperty("os.name").startsWith("Mac");

    UnixOperations(CommandRunner commands) {
        this.commands = commands;
    }

    @Override
    public void configureProxy() {
        var all = SystemEnvironment.get("all_proxy", SystemEnvironment.get("ALL_PROXY", ""));
        ProxySettings.configure(SystemEnvironment.get("http_proxy", all),
                SystemEnvironment.get("https_proxy", SystemEnvironment.get("HTTPS_PROXY", all)),
                SystemEnvironment.get("no_proxy", SystemEnvironment.get("NO_PROXY", "")));
    }

    @Override
    public Path defaultInstallDirectory() {
        return Path.of(SystemEnvironment.get("HOME", System.getProperty("user.home")), "fivetran-proxy-agent");
    }

    @Override
    public String managerScriptName() {
        return "proxy-agent-manager.sh";
    }

    @Override
    public String managerBinaryName() {
        return "manager";
    }

    @Override
    public long reservedMemoryMb() {
        return 1024;
    }

    @Override
    public Settings settings(int memoryMb, String windowsVersion) {
        return new Settings("settings.sh", "MEMORY_ALLOCATION_MB=" + memoryMb + "\n");
    }

    @Override
    public void checkWindowsVersionOverride(String version) {
        if (!version.isEmpty()) {
            throw new IllegalArgumentException("Invalid Windows version: " + version);
        }
    }

    @Override
    public String containerOs(List<String> errors) {
        return "linux";
    }

    @Override
    public boolean hasCommand(String name) throws IOException, InterruptedException {
        return commands.capture("which", name).succeeded();
    }

    @Override
    public void checkUser() throws IOException, InterruptedException {
        var userId = commands.capture("id", "-u");
        if (!userId.succeeded()) {
            throw new IOException("Unable to determine the current user.");
        }
        if (userId.output().strip().equals("0")) {
            throw new IOException("This script should not be run as root. Please run as a regular user.");
        }
    }

    @Override
    public void checkAutoStart(List<String> warnings) throws IOException, InterruptedException {
        if (isMac || !commands.capture("docker", "info", "--format", "{{.SecurityOptions}}").output().contains("rootless")) {
            return;
        }
        if (!hasCommand("loginctl")) {
            warnings.add("Rootless Docker detected but loginctl is not available. Ensure the agent starts on boot.");
            return;
        }
        var user = SystemEnvironment.get("USER", System.getProperty("user.name"));
        if (!commands.capture("loginctl", "show-user", user, "-p", "Linger", "--value").output().strip().equals("yes")) {
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
    public String windowsImageVariant() {
        throw new UnsupportedOperationException("Windows containers require a Windows host");
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
    public int start(Path manager) throws IOException, InterruptedException {
        return commands.run(manager.getParent(), List.of(manager.toString(), "start"));
    }
}
