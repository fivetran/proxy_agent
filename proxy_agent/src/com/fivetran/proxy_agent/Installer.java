package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Installs the proxy agent and delegates the first start to the legacy manager. */
final class Installer {
    private static final int DEFAULT_MEMORY_MB = 5120;
    private static final int MIN_CPU_COUNT = 4;
    private static final int MIN_DISK_MB = 2048;
    private static final int MIN_WINDOWS_DISK_MB = 12288;
    private static final String SCRIPT_ROOT = "https://raw.githubusercontent.com/fivetran/proxy_agent/main/";
    private static final String REGISTRY_URL =
            "https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list";
    private static final String MIN_DOCKER_VERSION = "20.10.17";
    private static final String UBUNTU_TAG_SUFFIX = "-ubuntu-26.04";
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern DOCKER_VERSION = Pattern.compile("Docker version (" + VERSION + ")");
    private static final Set<String> WINDOWS_VERSIONS = Set.of("ltsc2019", "ltsc2022", "ltsc2025");

    private final CommandRunner commands = new CommandRunner();
    private final Operations operations;
    private final HttpClient http;

    Installer(Operations operations, HttpClient http) {
        this.operations = operations;
        this.http = http;
    }

    void install(@Nullable Path configPath, @Nullable Path installDir, String windowsVersionOverride)
            throws IOException, InterruptedException {
        operations.checkUser();
        if (!CommandRunner.env("RUNTIME", "").equals("docker")) {
            throw new IOException("RUNTIME must be set to 'docker'.");
        }
        var options = configurePaths(configPath, installDir, windowsVersionOverride);
        var config = readConfig(options.config());
        var memoryMb = readMemoryMb();
        System.out.println("Installing Fivetran Proxy Agent...\n");
        var windowsVersion = checkPrerequisites(options, memoryMb);

        var directory = options.directory();
        if (Files.isDirectory(directory)) {
            System.out.println(directory + " already exists, will re-use it.");
        }
        var configDirectory = Files.createDirectories(directory.resolve("config"));
        Files.createDirectories(directory.resolve("logs"));
        var managerName = operations.isWindows() ? "proxy-agent-manager.ps1" : "proxy-agent-manager.sh";
        var manager = directory.resolve(managerName);
        installManager(manager);
        writeSettings(directory, options.windowsVersion(), memoryMb);
        installConfig(configDirectory, config);

        var suffix = windowsVersion.isEmpty() ? UBUNTU_TAG_SUFFIX : "-windows-" + windowsVersion;
        System.out.println("Resolving latest proxy agent version...");
        if (!windowsVersion.isEmpty()) {
            System.out.println("Using Windows image variant " + windowsVersion);
        } else if (operations.isWindows()) {
            System.out.println("Using Linux container image");
        }
        var version = getLatestVersion(suffix);
        Files.writeString(directory.resolve("version"), version + "\n");
        System.out.println("Using version " + version);
        installNativeBinary(directory);
        operations.start(manager);
        System.out.println("\nInstallation complete.");
        System.out.println("Install directory: " + directory);
    }

    private Options configurePaths(@Nullable Path config, @Nullable Path directory, String windowsVersion) {
        var variant = windowsVersion.toLowerCase(Locale.ROOT);
        if (!variant.isEmpty() && (!operations.isWindows() || !WINDOWS_VERSIONS.contains(variant))) {
            throw new IllegalArgumentException("Invalid Windows version: " + windowsVersion);
        }
        if (directory == null) {
            var home = CommandRunner.env(operations.isWindows() ? "USERPROFILE" : "HOME", System.getProperty("user.home"));
            directory = Path.of(home, "fivetran-proxy-agent");
        }
        return new Options(directory.toAbsolutePath().normalize(), config, variant);
    }

    private Config readConfig(@Nullable Path path) throws IOException, InterruptedException {
        var token = CommandRunner.env("TOKEN", "");
        if (path == null) {
            if (token.isEmpty()) {
                throw new IOException("Provide a config.json file or set TOKEN.");
            }
            return new Config(null, token);
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("Config file not found or not readable: " + path);
        }
        var content = Files.readAllBytes(path);
        var text = new String(content, UTF_8);
        if (!token.isEmpty() || !text.contains("\"proxy_server_uri\"")) {
            return new Config(content, "");
        }

        var credential = operations.legacyCredential(path, text);
        token = Base64.getEncoder().encodeToString(credential.getBytes(UTF_8));
        System.out.println("Detected WebSocket agent configuration. Fetching updated config from Fivetran...");
        return new Config(null, token);
    }

    private static int readMemoryMb() {
        var memory = CommandRunner.env("MEMORY_ALLOCATION_MB", "");
        var console = System.console();
        if (memory.isEmpty() && console != null) {
            var response = console.readLine(
                    "How much memory (in MB) should the proxy agent container use? [%d]: ", DEFAULT_MEMORY_MB);
            memory = response == null ? "" : response;
        }
        if (memory.isEmpty()) {
            return DEFAULT_MEMORY_MB;
        }
        try {
            if (memory.matches("[0-9]+")) {
                var value = Integer.parseInt(memory);
                if (value > 0) {
                    return value;
                }
            }
        } catch (NumberFormatException e) {
            // Values outside the range of the existing container settings are invalid.
        }
        throw new IllegalArgumentException("Invalid memory amount (must be a positive integer, in MB)");
    }

    private String checkPrerequisites(Options options, int memoryMb) throws IOException, InterruptedException {
        System.out.print("Checking prerequisites... ");
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var dockerAvailable = checkDocker(warnings, errors);
        var containerOs = "linux";
        if (dockerAvailable && operations.isWindows()) {
            var result = commands.docker("info", "--format", "{{.OSType}}");
            containerOs = result.output().strip();
            if (!result.succeeded() || !(containerOs.equals("linux") || containerOs.equals("windows"))) {
                errors.add("Unable to determine whether Docker is running Linux or Windows containers");
            }
        }
        if (!options.windowsVersion().isEmpty() && !containerOs.equals("windows")) {
            errors.add("-WindowsVersion can only be used when Docker is running Windows containers");
        }
        if (dockerAvailable) {
            operations.checkAutoStart(warnings);
        }
        checkResources(memoryMb, warnings);
        checkDisk(options.directory(), containerOs.equals("windows"), warnings);
        if (warnings.isEmpty() && errors.isEmpty()) {
            System.out.println("OK\n");
        } else {
            printMessages("WARNINGS", warnings);
            printMessages("ERRORS", errors);
            if (!errors.isEmpty()) {
                throw new IOException("Please resolve the above errors before proceeding.");
            }
        }
        if (!containerOs.equals("windows")) {
            return "";
        }
        return options.windowsVersion().isEmpty() ? operations.windowsImageVariant() : options.windowsVersion();
    }

    private boolean checkDocker(List<String> warnings, List<String> errors) throws IOException, InterruptedException {
        if (!operations.hasCommand("docker")) {
            errors.add("docker is not installed");
            return false;
        }
        var result = commands.docker("--version");
        if (!result.succeeded()) {
            errors.add("Failed to execute 'docker --version'. Docker may not be functioning properly");
            return false;
        }
        var matcher = DOCKER_VERSION.matcher(result.output());
        if (!matcher.find()) {
            warnings.add("Unable to determine Docker version");
        } else if (compareVersions(matcher.group(1), MIN_DOCKER_VERSION) < 0) {
            errors.add("Docker version " + matcher.group(1) + " does not meet the minimum requirement of "
                    + MIN_DOCKER_VERSION);
        }
        if (!commands.docker("info").succeeded()) {
            errors.add("Docker is installed but the Docker daemon is not accessible. Ensure Docker is running and your"
                    + " user has permission to access the Docker daemon.");
            return false;
        }
        return true;
    }

    private void checkResources(int memoryMb, List<String> warnings) throws IOException, InterruptedException {
        var cpus = operations.cpuCount();
        if (cpus > 0 && cpus < MIN_CPU_COUNT) {
            warnings.add("CPU count (" + cpus + ") is below the recommended minimum of " + MIN_CPU_COUNT);
        }
        if (memoryMb < DEFAULT_MEMORY_MB) {
            warnings.add("Configured container memory (" + memoryMb + "MB) is below the recommended minimum of "
                    + DEFAULT_MEMORY_MB + "MB");
        }
        var totalMb = operations.totalMemoryMb();
        var reservedMb = operations.isWindows() ? 3072 : 1024;
        if (totalMb == 0) {
            warnings.add("Unable to determine available memory");
        } else if (memoryMb > totalMb - reservedMb) {
            warnings.add("Configured container memory (" + memoryMb + "MB) leaves less than " + reservedMb
                    + "MB for the host (total RAM: " + totalMb + "MB)");
        }
    }

    private void checkDisk(Path directory, boolean usesWindowsContainers, List<String> warnings)
            throws IOException, InterruptedException {
        if (usesWindowsContainers) {
            var root = commands.docker("info", "--format", "{{.DockerRootDir}}");
            var dataRoot = root.output().strip();
            if (root.succeeded() && !dataRoot.isEmpty()) {
                directory = Path.of(dataRoot);
            } else {
                warnings.add("Unable to determine Docker's data root; checking disk space for " + directory + " instead");
            }
        }
        var target = operations.isWindows() && Files.exists(directory) ? directory : directory.getParent();
        if (target == null || !Files.isDirectory(target)) {
            if (operations.isWindows()) {
                warnings.add("Unable to determine available disk space: directory " + target + " does not exist");
            }
            return;
        }
        var minimumMb = usesWindowsContainers ? MIN_WINDOWS_DISK_MB : MIN_DISK_MB;
        long freeMb;
        try {
            freeMb = Files.getFileStore(target).getUsableSpace() / (1024 * 1024);
        } catch (IOException e) {
            warnings.add("Unable to determine available disk space for " + target);
            return;
        }
        if (freeMb < minimumMb) {
            var location = operations.isWindows() ? " on drive " + target.getRoot().toString().substring(0, 2) + " " : " ";
            warnings.add("Available disk space" + location + "(" + freeMb + "MB) is below the recommended minimum of "
                    + minimumMb + "MB");
        }
    }

    private static void printMessages(String title, List<String> messages) {
        if (!messages.isEmpty()) {
            System.out.println("\n" + title + ":");
            for (var message : messages) {
                System.out.println("  - " + message);
            }
        }
    }

    private void installManager(Path manager) throws IOException {
        System.out.println("Downloading management script...");
        var temp = Files.createTempFile(manager.getParent(), manager.getFileName() + ".", ".tmp");
        try {
            var url = CommandRunner.env("PROXY_AGENT_SCRIPT_URL", SCRIPT_ROOT + manager.getFileName());
            http.download(url, temp);
            operations.makeExecutable(temp);
            Files.move(temp, manager, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        // A baseline is also understood by legacy managers that support automatic updates.
        var state = manager.getParent().resolve(".manager-auto-update");
        try {
            Files.createDirectories(state);
            var tempState = Files.createTempFile(state, "download.", ".tmp");
            try {
                Files.writeString(tempState, sha256(Files.readAllBytes(manager)) + "\n");
                Files.move(tempState, state.resolve("download"), ATOMIC_MOVE, REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tempState);
            }
        } catch (IOException e) {
            // Existing script installers treat unavailable update metadata as best effort.
            System.err.println("WARNING: Could not record the installed manager hash.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void writeSettings(Path directory, String windowsVersion, int memoryMb) throws IOException {
        if (!operations.isWindows()) {
            Files.writeString(directory.resolve("settings.sh"), "MEMORY_ALLOCATION_MB=" + memoryMb + "\n");
            return;
        }
        var settings = "$MEMORY_ALLOCATION_MB = " + memoryMb + "\r\n";
        if (!windowsVersion.isEmpty()) {
            settings += "$WINDOWS_VERSION = '" + windowsVersion + "'\r\n";
        }
        Files.writeString(directory.resolve("settings.ps1"), settings);
    }

    private void installConfig(Path directory, Config config) throws IOException, InterruptedException {
        var contents = config.contents();
        var shouldFetch = contents == null;
        if (contents == null) {
            var api = CommandRunner.env("FIVETRAN_API_URL", "https://api.fivetran.com").replaceAll("/+$", "");
            System.out.println("Fetching agent config from Fivetran...");
            contents = http.configure(api + "/proxy-agent/configure", config.token());
            operations.validateFetchedConfig(contents);
        }
        var destination = directory.resolve("config.json");
        var temp = operations.createProtectedConfig(directory);
        try {
            Files.write(temp, contents);
            Files.move(temp, destination, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        if (!shouldFetch) {
            System.out.println("Config copied to " + destination);
        }
    }

    private String getLatestVersion(String suffix) throws IOException {
        var tags = http.registryTags(CommandRunner.env("PROXY_AGENT_REGISTRY_TAGS_URL", REGISTRY_URL));
        var pattern = Pattern.compile("(" + VERSION + ")" + Pattern.quote(suffix));
        var latest = "";
        for (var tag : tags) {
            var matcher = pattern.matcher(tag);
            if (!matcher.matches()) {
                continue;
            }
            var version = matcher.group(1);
            if (latest.isEmpty() || compareVersions(version, latest) > 0) {
                latest = version;
            }
        }
        if (latest.isEmpty()) {
            throw new IOException("No published proxy agent image tags found for " + suffix.substring(1));
        }
        return latest;
    }

    private static int compareVersions(String left, String right) {
        var leftParts = left.split("\\.");
        var rightParts = right.split("\\.");
        for (var i = 0; i < leftParts.length; i++) {
            var compare = new BigInteger(leftParts[i]).compareTo(new BigInteger(rightParts[i]));
            if (compare != 0) {
                return compare;
            }
        }
        return 0;
    }

    private void installNativeBinary(Path directory) throws IOException {
        var executable = CommandRunner.env("PROXY_AGENT_INSTALLER_BINARY", "");
        if (executable.isEmpty() && "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            executable = ProcessHandle.current().info().command().orElse("");
        }
        if (executable.isEmpty()) {
            return;
        }
        var source = Path.of(executable);
        var target = directory.resolve(operations.isWindows() ? "manager.exe" : "manager");
        if (Files.exists(target) && (Files.isSameFile(source, target) || Files.mismatch(source, target) == -1)) {
            return;
        }
        var temp = Files.createTempFile(directory, "manager.", ".tmp");
        try {
            Files.copy(source, temp, REPLACE_EXISTING);
            operations.makeExecutable(temp);
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Command-line paths and the optional Windows image override. */
    private record Options(Path directory, @Nullable Path config, String windowsVersion) {}

    /** Config bytes for a local file, or a token for the configuration endpoint. */
    private record Config(byte @Nullable [] contents, String token) {}
}
