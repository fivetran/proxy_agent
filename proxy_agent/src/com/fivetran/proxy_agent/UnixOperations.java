package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Host operations shared by Linux and macOS. */
class UnixOperations implements OsOperations {
    private final CommandRunner commands;
    private final Docker docker;
    private final boolean isMac = System.getProperty("os.name").startsWith("Mac");

    UnixOperations(CommandRunner commands, Docker docker) {
        this.commands = commands;
        this.docker = docker;
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
    public String managerReleaseAsset() throws IOException {
        if (isMac) {
            throw new IOException("A manager download URL and SHA-256 digest are required on macOS.");
        }
        return switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "manager-linux-x64";
            case "aarch64", "arm64" -> "manager-linux-aarch64";
            default -> throw new IOException("No native manager is available for this host architecture.");
        };
    }

    @Override
    public boolean validManagerScript(Path script) throws IOException, InterruptedException {
        return commands.capture("bash", "-n", script.toString()).succeeded();
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
    public ContainerSettings containerSettings(Path directory) throws IOException, InterruptedException {
        var path = directory.resolve("settings.sh");
        if (!Files.isRegularFile(path)) {
            return new ContainerSettings(SystemEnvironment.get("MEMORY_ALLOCATION_MB", "5120"), "");
        }
        var result = commands.capture("bash", "-e", "-c",
                "source \"$1\" >/dev/null; printf '%s\\n' \"${MEMORY_ALLOCATION_MB:-5120}\"", "manager", path.toString());
        if (!result.succeeded()) {
            throw new IOException("Could not read settings from " + path);
        }
        return new ContainerSettings(result.output().strip(), "");
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
        if (isMac || !docker.capture("info", "--format", "{{.SecurityOptions}}").output().contains("rootless")) {
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
