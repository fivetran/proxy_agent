package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.sun.management.OperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
    private static final String MIN_DOCKER_VERSION = "20.10.17";
    private static final String UBUNTU_TAG_SUFFIX = "-ubuntu-26.04";
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern DOCKER_VERSION = Pattern.compile("Docker version (" + VERSION + ")");
    private static final Set<String> WINDOWS_VERSIONS = Set.of("ltsc2019", "ltsc2022", "ltsc2025");

    private final Manager manager;
    private final OsOperations operations;
    private final Docker docker;
    private final DiskSpace diskSpace;

    Installer(Manager manager, DiskSpace diskSpace) {
        this.manager = manager;
        this.operations = manager.operations();
        this.docker = manager.docker();
        this.diskSpace = diskSpace;
    }

    void install(@Nullable Path configPath, @Nullable Path installDir, String windowsVersionOverride)
            throws IOException, InterruptedException {
        operations.checkUser();
        if (!SystemEnvironment.get("RUNTIME", "").equals("docker")) {
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
        var script = directory.resolve(operations.managerScriptName());
        try (var files = new StagedFiles()) {
            System.out.println("Downloading management script...");
            var stagedScript = files.create(script);
            manager.downloadManagerScript(stagedScript);
            operations.makeExecutable(stagedScript);
            var contents = configContents(config);

            var suffix = windowsVersion.isEmpty() ? UBUNTU_TAG_SUFFIX : "-windows-" + windowsVersion;
            System.out.println("Resolving latest proxy agent version...");
            if (!windowsVersion.isEmpty()) {
                System.out.println("Using Windows image variant " + windowsVersion);
            } else {
                operations.describeLinuxImage();
            }
            var version = getLatestVersion(suffix);

            var settings = operations.settings(memoryMb, options.windowsVersion());
            Files.writeString(files.create(directory.resolve(settings.fileName())), settings.contents());
            Files.writeString(files.create(directory.resolve("version")), version + "\n");
            stageNativeBinary(directory, files);
            var destination = configDirectory.resolve("config.json");
            var stagedConfig = operations.createProtectedConfig(configDirectory);
            files.add(stagedConfig, destination);
            Files.write(stagedConfig, contents);

            // Publish config last, after the other files the manager needs are in place.
            files.install();
            if (config.contents() != null) {
                System.out.println("Config copied to " + destination);
            }
            System.out.println("Using version " + version);
        }
        recordManagerHash(script);
        manager.startAgent(script);
        System.out.println("\nInstallation complete.");
        System.out.println("Install directory: " + directory);
    }

    private Options configurePaths(@Nullable Path config, @Nullable Path directory, String windowsVersion) {
        var variant = windowsVersion.toLowerCase(Locale.ROOT);
        if (!variant.isEmpty() && !WINDOWS_VERSIONS.contains(variant)) {
            throw new IllegalArgumentException("Invalid Windows version: " + windowsVersion);
        }
        operations.checkWindowsVersionOverride(windowsVersion);
        if (directory == null) {
            directory = operations.defaultInstallDirectory();
        }
        return new Options(directory.toAbsolutePath().normalize(), config, variant);
    }

    private static Config readConfig(@Nullable Path path) throws IOException {
        var token = SystemEnvironment.get("TOKEN", "");
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
        var credential = token.isEmpty() ? Manager.legacyCredential(content) : null;
        if (credential == null) {
            return new Config(content, "");
        }

        token = Base64.getEncoder().encodeToString(credential.getBytes(UTF_8));
        System.out.println("Detected WebSocket agent configuration. Fetching updated config from Fivetran...");
        return new Config(null, token);
    }

    private static int readMemoryMb() {
        var memory = SystemEnvironment.get("MEMORY_ALLOCATION_MB", "");
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
        var containerOs = dockerAvailable ? operations.containerOs(errors) : "linux";
        if (!options.windowsVersion().isEmpty() && !containerOs.equals("windows")) {
            errors.add("-WindowsVersion can only be used when Docker is running Windows containers");
        }
        if (dockerAvailable) {
            operations.checkAutoStart(warnings);
        }
        checkResources(memoryMb, warnings);
        checkDisk(options.directory(), containerOs, warnings);
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
        var result = docker.capture("--version");
        if (!result.succeeded()) {
            errors.add("Failed to execute 'docker --version'. Docker may not be functioning properly");
            return false;
        }
        var matcher = DOCKER_VERSION.matcher(result.output());
        if (!matcher.find()) {
            warnings.add("Unable to determine Docker version");
        } else if (ReleaseVersion.compare(matcher.group(1), MIN_DOCKER_VERSION) < 0) {
            errors.add("Docker version " + matcher.group(1) + " does not meet the minimum requirement of "
                    + MIN_DOCKER_VERSION);
        }
        if (!docker.capture("info").succeeded()) {
            errors.add("Docker is installed but the Docker daemon is not accessible. Ensure Docker is running and your"
                    + " user has permission to access the Docker daemon.");
            return false;
        }
        return true;
    }

    private void checkResources(int memoryMb, List<String> warnings) throws IOException {
        var cpus = operations.cpuCount();
        if (cpus > 0 && cpus < MIN_CPU_COUNT) {
            warnings.add("CPU count (" + cpus + ") is below the recommended minimum of " + MIN_CPU_COUNT);
        }
        if (memoryMb < DEFAULT_MEMORY_MB) {
            warnings.add("Configured container memory (" + memoryMb + "MB) is below the recommended minimum of "
                    + DEFAULT_MEMORY_MB + "MB");
        }
        var system = ManagementFactory.getOperatingSystemMXBean();
        var totalMb = system instanceof OperatingSystemMXBean memory
                ? Math.max(0, memory.getTotalMemorySize()) / (1024 * 1024)
                : 0;
        var reservedMb = operations.reservedMemoryMb();
        if (totalMb == 0) {
            warnings.add("Unable to determine available memory");
        } else if (memoryMb > totalMb - reservedMb) {
            warnings.add("Configured container memory (" + memoryMb + "MB) leaves less than " + reservedMb
                    + "MB for the host (total RAM: " + totalMb + "MB)");
        }
    }

    private void checkDisk(Path directory, String containerOs, List<String> warnings)
            throws IOException, InterruptedException {
        if (containerOs.equals("windows")) {
            var root = docker.capture("info", "--format", "{{.DockerRootDir}}");
            var dataRoot = root.output().strip();
            if (root.succeeded() && !dataRoot.isEmpty()) {
                directory = Path.of(dataRoot);
            } else {
                warnings.add("Unable to determine Docker's data root; checking disk space for " + directory + " instead");
            }
        }
        var target = operations.diskCheckTarget(directory);
        if (target == null || !Files.isDirectory(target)) {
            operations.warnMissingDiskTarget(target, warnings);
            return;
        }
        var minimumMb = containerOs.equals("windows") ? MIN_WINDOWS_DISK_MB : MIN_DISK_MB;
        long freeMb;
        try {
            freeMb = diskSpace.usableMb(target);
        } catch (IOException e) {
            warnings.add("Unable to determine available disk space for " + target);
            return;
        }
        if (freeMb < minimumMb) {
            warnings.add("Available disk space for " + target + " (" + freeMb + "MB) is below the recommended minimum of "
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

    private static void recordManagerHash(Path manager) {
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

    private byte[] configContents(Config config) throws IOException {
        var contents = config.contents();
        if (contents != null) {
            return contents;
        }
        System.out.println("Fetching agent config from Fivetran...");
        return manager.fetchConfiguration(config.token());
    }

    private String getLatestVersion(String suffix) throws IOException {
        var tags = manager.registryTags();
        var pattern = Pattern.compile("(" + VERSION + ")" + Pattern.quote(suffix));
        var latest = "";
        for (var tag : tags) {
            var matcher = pattern.matcher(tag);
            if (!matcher.matches()) {
                continue;
            }
            var version = matcher.group(1);
            if (latest.isEmpty() || ReleaseVersion.compare(version, latest) > 0) {
                latest = version;
            }
        }
        if (latest.isEmpty()) {
            throw new IOException("No published proxy agent image tags found for " + suffix.substring(1));
        }
        return latest;
    }

    private void stageNativeBinary(Path directory, StagedFiles files) throws IOException {
        var executable = SystemEnvironment.get("PROXY_AGENT_INSTALLER_BINARY", "");
        if (executable.isEmpty() && "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            executable = ProcessHandle.current().info().command().orElse("");
        }
        if (executable.isEmpty()) {
            return;
        }
        var source = Path.of(executable);
        var target = directory.resolve(operations.managerBinaryName());
        if (Files.exists(target) && (Files.isSameFile(source, target) || Files.mismatch(source, target) == -1)) {
            return;
        }
        var temp = files.create(target);
        Files.copy(source, temp, REPLACE_EXISTING);
        operations.makeExecutable(temp);
    }

    /** Command-line paths and the optional Windows image override. */
    private record Options(Path directory, @Nullable Path config, String windowsVersion) {}

    /** Config bytes for a local file, or a token for the configuration endpoint. */
    private record Config(byte @Nullable [] contents, String token) {}

    /** Measures usable disk space at the selected installation or Docker data path. */
    @FunctionalInterface
    interface DiskSpace {
        long usableMb(Path target) throws IOException;

        static long fileStore(Path target) throws IOException {
            return Files.getFileStore(target).getUsableSpace() / (1024 * 1024);
        }
    }
}
