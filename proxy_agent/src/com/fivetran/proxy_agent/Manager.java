package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Proxy agent operations shared by manager commands. */
final class Manager {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private static final String SCRIPT_ROOT = "https://raw.githubusercontent.com/fivetran/proxy_agent/main/";
    private static final String IMAGE =
            "us-docker.pkg.dev/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent";
    private static final String LINUX_SUFFIX = "-ubuntu-26.04";
    private static final Pattern RELEASE =
            Pattern.compile("([0-9]+\\.[0-9]+\\.[0-9]+)(-ubuntu-26\\.04|-windows-(ltsc2019|ltsc2022|ltsc2025))?");
    private static final String HEALTH_STATUS = "{{.State.Health.Status}}";
    private static final String WINDOWS_HEALTH_SCRIPT = """
            $path = $env:HEARTBEAT_PATH
            if (-not (Test-Path -LiteralPath $path)) { exit 0 }
            $line = Get-Content -LiteralPath $path -ErrorAction Stop | Select-Object -First 1
            if ($line -match '^HEARTBEAT_EXPIRE_AT=(\\d+)$' -and [int64]$Matches[1] -gt [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) { exit 0 }
            exit 1
            """;
    private static final String REGISTRY_URL =
            "https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list";
    private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OsOperations operations;
    private final Docker docker;
    private final HttpClient http;

    Manager(OsOperations operations, Docker docker, HttpClient http) {
        this.operations = operations;
        this.docker = docker;
        this.http = http;
    }

    OsOperations operations() {
        return operations;
    }

    Docker docker() {
        return docker;
    }

    /** Downloads the native release and the corresponding platform script. */
    int updateManager(Path directory) throws IOException, InterruptedException {
        return new ManagerUpdate(operations, http).run(directory,
                SystemEnvironment.get("PROXY_AGENT_MANAGER_BINARY_URL", ""),
                SystemEnvironment.get("PROXY_AGENT_MANAGER_BINARY_SHA256", ""),
                SystemEnvironment.get("PROXY_AGENT_SCRIPT_URL", ""));
    }

    /** Prints the Docker status table for the installed agent. */
    int status(Path directory) throws IOException, InterruptedException {
        return docker.run(directory, statusArguments(containerName(directory)));
    }

    /** Streams Docker logs until the caller or Docker ends the session. */
    int logs(Path directory) throws IOException, InterruptedException {
        return docker.run(directory, "logs", "-f", containerName(directory));
    }

    /** Stops and removes the agent container if it exists. */
    int stop(Path directory) throws IOException, InterruptedException {
        var container = containerName(directory);
        log(directory, "Stopping " + container + "...");
        return stopContainer(directory, container);
    }

    /** Starts the pinned image and waits for Docker to report the container healthy. */
    int start(Path directory) throws IOException, InterruptedException {
        var agent = agentContainer(directory);
        pinVersion(directory, agent.version());
        return startContainer(directory, agent, agent.version());
    }

    /** Replaces the running container with the pinned release after preflight succeeds. */
    int restart(Path directory) throws IOException, InterruptedException {
        var agent = agentContainer(directory);
        log(directory, "Restarting " + agent.name() + "...");
        pinVersion(directory, agent.version());
        return startContainer(directory, agent, agent.version());
    }

    private AgentContainer agentContainer(Path directory) throws IOException, InterruptedException {
        var agentId = agentId(directory);
        var pinned = pinnedVersion(directory);
        var release = RELEASE.matcher(pinned);
        if (!release.matches()) {
            throw new IOException("Invalid proxy agent version '" + pinned + "' in " + directory.resolve("version")
                    + ". Expected <major>.<minor>.<patch>.");
        }
        var settings = operations.containerSettings(directory);
        var memory = settings.memoryMb();
        if (!memory.matches("[0-9]+") || memory.matches("0+")) {
            throw new IOException("Invalid MEMORY_ALLOCATION_MB (must be a positive integer, in MB)");
        }
        var errors = new ArrayList<String>();
        var containerOs = operations.containerOs(errors);
        if (!errors.isEmpty()) {
            throw new IOException(errors.get(0));
        }
        var windows = containerOs.equals("windows");
        var variant = release.group(3);
        if (!windows && variant != null) {
            throw new IOException("Windows proxy-agent image tags require Docker to run Windows containers.");
        }
        if (windows && LINUX_SUFFIX.equals(release.group(2))) {
            log(directory, "WARNING: " + directory.resolve("version") + " pins the Linux image tag " + pinned
                    + ", but Docker is running Windows containers. Using the Windows image for version "
                    + release.group(1) + " instead.");
        }
        if (windows && variant != null && !variant.equals(settings.windowsVersion())) {
            operations.saveWindowsVariant(directory, variant);
        }
        if (windows && variant == null) {
            variant = settings.windowsVersion().isEmpty() ? operations.windowsImageVariant() : settings.windowsVersion();
        }
        return new AgentContainer(agentId, release.group(1), SystemEnvironment.get("PROXY_AGENT_IMAGE", IMAGE),
                windows ? "-windows-" + variant : LINUX_SUFFIX, memory, windows ? ContainerOs.WINDOWS : ContainerOs.LINUX);
    }

    private static void pinVersion(Path directory, String version) throws IOException {
        var target = directory.resolve("version");
        try (var files = new StagedFiles()) {
            Files.writeString(files.create(target), version + System.lineSeparator());
            files.install();
        }
    }

    private int startContainer(Path directory, AgentContainer agent, String version)
            throws IOException, InterruptedException {
        var container = agent.name();
        var image = agent.imageTag(version);
        log(directory, "Starting " + container + " (version: " + version + ")...");
        if (stopContainer(directory, container) != 0) {
            return 1;
        }
        var arguments = new ArrayList<>(List.of(
                "run", "-d", "--name", container, "--restart", "unless-stopped",
                "--label", "fivetran=proxy-agent", "--label", "proxy_agent_id=" + agent.id(),
                "--env", "HEARTBEAT_EXPIRY_SECONDS=30", "--health-interval", "10s",
                "--health-retries", "3", "--health-start-period", "30s"));
        if (agent.os() == ContainerOs.WINDOWS) {
            var check = Base64.getEncoder().encodeToString(WINDOWS_HEALTH_SCRIPT.getBytes(UTF_16LE));
            arguments.addAll(List.of("--env", "HEARTBEAT_PATH=C:/app/logs/proxy-agent-heartbeat.txt",
                    "--health-cmd", "powershell.exe -NoProfile -EncodedCommand " + check, "--health-timeout", "15s"));
        } else {
            arguments.addAll(List.of("--env", "HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt", "--health-cmd",
                    "[ ! -f $HEARTBEAT_PATH ] || { . $HEARTBEAT_PATH && [ $(date +%s) -lt $HEARTBEAT_EXPIRE_AT ]; }",
                    "--health-timeout", "3s"));
        }
        arguments.addAll(agent.runArguments(directory));
        arguments.addAll(List.of(image, "-i", agent.configPath()));
        if (docker.run(directory, arguments.toArray(String[]::new)) != 0) {
            log(directory, "Error: Failed to start container " + container);
            return 1;
        }

        var timeout = 60;
        log(directory, "Waiting for " + container + " to become healthy (timeout: " + timeout + "s)...");
        for (var elapsed = 0; ; elapsed++) {
            var result = docker.capture("inspect", "-f", HEALTH_STATUS, container);
            var health = result.succeeded() ? result.output().strip() : "unknown";
            if (health.equals("healthy")) {
                docker.run(directory, statusArguments(container));
                log(directory, "Success: " + container + " is healthy.");
                return 0;
            }
            if (!health.equals("starting")) {
                log(directory, "Error: " + container + " entered status: " + health);
                docker.run(directory, "logs", container);
                return 1;
            }
            if (elapsed >= timeout) {
                log(directory, "Error: " + container + " did not become healthy within " + timeout + "s");
                docker.run(directory, "logs", container);
                stopContainer(directory, container);
                return 1;
            }
            Thread.sleep(1000);
        }
    }

    private enum ContainerOs {
        LINUX("/config/config.json", "/app/logs", "config/config.json", "/config/config.json"),
        WINDOWS("C:/config/config.json", "C:/app/logs", "config", "C:/config");

        final String configPath;
        final String logDirectory;
        final String configSource;
        final String configTarget;

        ContainerOs(String configPath, String logDirectory, String configSource, String configTarget) {
            this.configPath = configPath;
            this.logDirectory = logDirectory;
            this.configSource = configSource;
            this.configTarget = configTarget;
        }
    }

    private record AgentContainer(String id, String version, String repository, String suffix, String memory,
                                  ContainerOs os) {
        String name() {
            return "proxy-agent-" + id;
        }

        String imageTag(String release) {
            return repository + ":" + release + suffix;
        }

        String configPath() {
            return os.configPath;
        }

        String logDirectory() {
            return os.logDirectory;
        }

        List<String> runArguments(Path directory) throws IOException {
            var logs = Files.createDirectories(directory.resolve("logs"));
            var config = directory.resolve(os.configSource);
            return List.of("--memory=" + memory + "m", "--env", "IS_DOCKER=true", "--env",
                    "LOG_FOLDER_PATH=" + logDirectory(), "-v",
                    config.toString().replace('\\', '/') + ":" + os.configTarget + ":ro",
                    "-v", logs.toString().replace('\\', '/') + ":" + logDirectory());
        }
    }

    private int stopContainer(Path directory, String container) throws IOException, InterruptedException {
        if (!docker.capture("inspect", container).succeeded()) {
            return 0;
        }
        var stop = docker.capture("stop", container);
        if (!stop.succeeded()) {
            System.err.println("ERROR: Failed to stop " + container + ".");
            return stop.status();
        }
        var remove = docker.capture("rm", container);
        if (!remove.succeeded()) {
            System.err.println("ERROR: Failed to remove " + container + ".");
            return remove.status();
        }
        log(directory, "Stopped " + container + ".");
        return 0;
    }

    private static void log(Path directory, String message) throws IOException {
        System.out.println(message);
        var logs = Files.createDirectories(directory.resolve("logs"));
        Files.writeString(logs.resolve("proxy-agent-manager.log"),
                LOG_TIMESTAMP.format(Instant.now()) + " UTC - " + message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String[] statusArguments(String container) {
        return new String[] {"ps", "-a", "--filter", "name=" + container,
                "--format", "table {{.Names}}\\t{{.Image}}\\t{{.Status}}"};
    }

    private static String containerName(Path directory) throws IOException {
        var id = agentId(directory);
        pinnedVersion(directory);
        return "proxy-agent-" + id;
    }

    private static String agentId(Path directory) throws IOException {
        var config = directory.resolve("config/config.json");
        if (!Files.isRegularFile(config)) {
            throw new IOException("Config file not found: " + config);
        }
        String agentId;
        try {
            agentId = readConfigFields(Files.readAllBytes(config)).agentId();
        } catch (IOException e) {
            throw new IOException("Failed to parse config file: " + config, e);
        }
        if (agentId == null || agentId.isBlank() || agentId.equals("null")) {
            throw new IOException("'agent_id' missing in " + config);
        }
        return agentId;
    }

    private static String pinnedVersion(Path directory) throws IOException {
        var versionFile = directory.resolve("version");
        if (!Files.isRegularFile(versionFile)) {
            throw new IOException(versionFile + " not found.");
        }
        var version = Files.readString(versionFile).replace("\uFEFF", "").strip();
        if (version.isEmpty()) {
            throw new IOException(versionFile + " is empty.");
        }
        return version;
    }

    void downloadManagerScript(Path target) throws IOException {
        var url = SystemEnvironment.get("PROXY_AGENT_SCRIPT_URL", SCRIPT_ROOT + operations.managerScriptName());
        try {
            http.download(url, target);
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    List<String> registryTags() throws IOException {
        try {
            var bytes = http.get(SystemEnvironment.get("PROXY_AGENT_REGISTRY_TAGS_URL", REGISTRY_URL));
            var tags = new ArrayList<String>();
            try (var parser = JSON.createParser(bytes)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Expected a registry response object");
                }
                for (var field = parser.nextFieldName(); field != null; field = parser.nextFieldName()) {
                    if (parser.nextToken() == JsonToken.START_ARRAY && field.equals("tags")) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                                throw new IOException("Expected a registry tag");
                            }
                            tags.add(parser.getText());
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                if (!parser.hasToken(JsonToken.END_OBJECT) || parser.nextToken() != null) {
                    throw new IOException("Invalid registry response");
                }
            }
            return tags;
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
    }

    byte[] fetchConfiguration(String token) throws IOException {
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("Invalid TOKEN");
        }
        var api = SystemEnvironment.get("FIVETRAN_API_URL", "https://api.fivetran.com").replaceAll("/+$", "");
        HttpClient.Response response;
        try {
            response = http.post(api + "/proxy-agent/configure",
                    Map.of("Authorization", "Basic " + token, "Accept", "application/json"));
        } catch (IOException e) {
            throw new IOException("Failed to connect to configure endpoint", e);
        }
        if (response.status() == 401) {
            throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
        }
        if (response.status() != 200) {
            throw new IOException("Configure endpoint returned HTTP " + response.status());
        }
        ConfigFields fields;
        try {
            fields = readConfigFields(response.body());
        } catch (IOException e) {
            throw new IOException("Configure endpoint returned an invalid JSON response", e);
        }
        if (fields.agentId() == null || fields.agentId().isBlank()) {
            throw new IOException("Configure endpoint response is missing a nonempty string agent_id");
        }
        return response.body();
    }

    /** Reads a saved WebSocket agent's credentials if the config needs to be migrated. */
    static @Nullable String legacyCredential(byte[] contents) throws IOException {
        ConfigFields fields;
        try {
            fields = readConfigFields(contents);
        } catch (IOException e) {
            throw new IOException("Config file does not contain a valid JSON object", e);
        }
        if (!fields.legacy()) {
            return null;
        }
        if (fields.agentId() == null || fields.agentId().isEmpty()
                || fields.authToken() == null || fields.authToken().isEmpty()) {
            throw new IOException("Could not extract credentials from WebSocket config");
        }
        return fields.agentId() + ":" + fields.authToken();
    }

    private static ConfigFields readConfigFields(byte[] bytes) throws IOException {
        String agentId = null;
        String authToken = null;
        var legacy = false;
        try (var parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected a config object");
            }
            for (var field = parser.nextFieldName(); field != null; field = parser.nextFieldName()) {
                var type = parser.nextToken();
                switch (field) {
                    case "agent_id" -> agentId = type == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "auth_token" -> authToken = type == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "proxy_server_uri" -> legacy = true;
                    default -> {}
                }
                parser.skipChildren();
            }
            if (!parser.hasToken(JsonToken.END_OBJECT) || parser.nextToken() != null) {
                throw new IOException("Invalid config object");
            }
        }
        return new ConfigFields(agentId, authToken, legacy);
    }

    void startAgent(Path script) throws IOException, InterruptedException {
        if (operations.start(script) != 0) {
            throw new IOException("Installation complete, but agent failed to start.");
        }
    }

    private record ConfigFields(@Nullable String agentId, @Nullable String authToken, boolean legacy) {}
}
