package com.fivetran.proxy_agent;

import static com.fivetran.proxy_agent.ProductTestSupport.EXTENSION;
import static com.fivetran.proxy_agent.ProductTestSupport.MANAGER_SCRIPT;
import static com.fivetran.proxy_agent.ProductTestSupport.SHELL;
import static com.fivetran.proxy_agent.ProductTestSupport.WINDOWS;
import static com.fivetran.proxy_agent.ProductTestSupport.digest;
import static com.fivetran.proxy_agent.ProductTestSupport.environment;
import static com.fivetran.proxy_agent.ProductTestSupport.repository;
import static com.fivetran.proxy_agent.ProductTestSupport.run;
import static com.fivetran.proxy_agent.ProductTestSupport.script;
import static com.fivetran.proxy_agent.ProductTestSupport.send;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the packaged native manager, real bootstrappers, and real Docker lifecycle. */
public final class InstalledManagerTest {
    private static final String PREVIOUS = "1.2.3";
    private static final String LATEST = "1.2.4";
    private static final String IMAGE = "proxy-agent-stub";
    private static final String CONTAINER = "proxy-agent-test-agent";
    private static final String TOKEN = Base64.getEncoder().encodeToString("test-agent:secret".getBytes(UTF_8));
    private static byte[] executable;
    private static String executablePath;
    private static String suffix;
    private static String windowsVersion;
    private static String memory;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private ProductTestSupport.Server server;
    private Map<String, String> env;
    private Path directory;
    private byte[] config;
    private volatile String responseOverride;
    private volatile int registryStatus = 200;

    @BeforeClass
    public static void buildImages() throws Exception {
        executablePath = System.getenv("PROXY_AGENT_MANAGER_BIN");
        if (executablePath == null) {
            throw new AssertionError("Set PROXY_AGENT_MANAGER_BIN to the packaged native manager.");
        }
        executable = Files.readAllBytes(Path.of(executablePath));
        if (WINDOWS) {
            var system = run(List.of(SHELL, "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_OperatingSystem).BuildNumber"), environment(), 30);
            system.expect(0);
            windowsVersion = switch (system.output().strip()) {
                case "17763" -> "ltsc2019";
                case "20348" -> "ltsc2022";
                case "26100" -> "ltsc2025";
                default -> throw new AssertionError("Unsupported test host build " + system.output());
            };
        }
        suffix = WINDOWS ? "-windows-" + windowsVersion : "-ubuntu-26.04";
        memory = WINDOWS ? "512" : "256";
        var context = repository().resolve("tests/stub");
        var build = new ArrayList<>(List.of("build", "-q"));
        if (WINDOWS) {
            build.addAll(List.of("--build-arg", "BASE_TAG=" + windowsVersion));
        }
        build.addAll(List.of("-f", context.resolve("Dockerfile." + (WINDOWS ? "windows" : "linux")).toString(),
                "-t", image(PREVIOUS), context.toString()));
        docker(build.toArray(String[]::new)).expect(0);
        docker("tag", image(PREVIOUS), image(LATEST)).expect(0);
    }

    @Before
    public void startEndpoints() throws Exception {
        directory = temporary.getRoot().toPath().resolve("proxy agent installed");
        config = Files.readAllBytes(fixture("config.json"));
        server = new ProductTestSupport.Server("127.0.0.1", this::serve);
        env = environment();
        env.putAll(Map.of("RUNTIME", "docker", "MEMORY_ALLOCATION_MB", memory, "PROXY_AGENT_IMAGE", IMAGE,
                "PROXY_AGENT_REGISTRY_TAGS_URL", server.url() + "/v2/stub/tags/list",
                "PROXY_AGENT_SCRIPT_URL", server.url() + "/" + MANAGER_SCRIPT, "FIVETRAN_API_URL", server.url(),
                "PROXY_AGENT_MANAGER_BIN", executablePath));
    }

    @After
    public void cleanUp() throws Exception {
        try {
            if (server != null) {
                docker("rm", "-f", CONTAINER);
            }
        } finally {
            if (server != null) {
                server.close();
            }
        }
    }

    @Test
    public void installsTheVerifiedDownloadAndRunsTheManagerLifecycle() throws Exception {
        env.remove("PROXY_AGENT_MANAGER_BIN");
        env.put("PROXY_AGENT_MANAGER_BINARY_URL", server.url() + "/manager");
        env.put("PROXY_AGENT_MANAGER_BINARY_SHA256", digest(executable));
        install(fixture("config.json"), null, false).expect(0);
        assertRunning(image(LATEST));
        assertVersion(LATEST);
        assertThat(Files.readAllBytes(directory.resolve("config/config.json")), is(config));
        assertThat(Files.readString(directory.resolve("settings." + EXTENSION)), containsString(
                WINDOWS ? "$MEMORY_ALLOCATION_MB = " + memory : "MEMORY_ALLOCATION_MB=" + memory));
        assertThat(dockerOutput("inspect", "-f", "{{.HostConfig.Memory}}", CONTAINER),
                is(Long.toString(Long.parseLong(memory) * 1024 * 1024)));
        if (!WINDOWS) {
            assertThat(Files.getPosixFilePermissions(directory.resolve("config/config.json")),
                    is(PosixFilePermissions.fromString("rw-------")));
        }
        assertThat(manage(0, "status"), containsString(CONTAINER));
        for (var pin : List.of(PREVIOUS, PREVIOUS + suffix)) {
            Files.writeString(directory.resolve("version"), pin);
            manage(0, "start");
            assertRunning(image(PREVIOUS));
            assertVersion(PREVIOUS);
        }
        if (WINDOWS) {
            assertThat(Files.readString(directory.resolve("settings.ps1")),
                    containsString("$WINDOWS_VERSION = '" + windowsVersion + "'"));
        }
        manage(0, "upgrade");
        assertRunning(image(LATEST));
        assertVersion(LATEST);
        assertThat(manage(0, "upgrade"), containsString("Already running the latest version"));
        manage(0, "restart");
        var doctor = manage(1, "doctor", "--region", "us-east-1");
        assertThat(doctor, containsString("stub doctor stderr"));
        assertThat(doctor, containsString("stub doctor report for options: --region us-east-1"));
        assertThat(Files.isRegularFile(directory.resolve("logs/doctor-stub.log")), is(true));
        assertThat(Files.isRegularFile(directory.resolve("logs/doctor-stub.zip")), is(true));
        try (var reports = Files.newDirectoryStream(directory.resolve("logs"), "proxy-agent-manager-doctor-*.log")) {
            assertThat(reports.iterator().hasNext(), is(false));
        }
        assertRunning(image(LATEST));
        manage(0, "stop");
        assertThat(dockerOutput("ps", "-a", "--filter", "name=" + CONTAINER, "--format", "{{.Names}}"), is(""));
    }

    @Test
    public void installsWithATokenWithoutLeakingIt() throws Exception {
        var result = install(null, TOKEN, false);
        result.expect(0);
        assertThat(result.output(), not(containsString(TOKEN)));
        assertThat(Files.readAllBytes(directory.resolve("config/config.json")), is(config));
        assertRunning(image(LATEST));
    }

    @Test
    public void migratesAWebSocketConfiguration() throws Exception {
        install(fixture("config-websocket.json"), null, WINDOWS).expect(0);
        assertThat(Files.readAllBytes(directory.resolve("config/config.json")), is(config));
        if (WINDOWS) {
            assertThat(Files.readString(directory.resolve("settings.ps1")),
                    containsString("$WINDOWS_VERSION = '" + windowsVersion + "'"));
        }
        assertRunning(image(LATEST));
    }

    @Test
    public void rollsBackAfterARealDockerPullFailure() throws Exception {
        install(fixture("config.json"), null, false).expect(0);
        var localImage = server.url().substring("http://".length()) + "/" + IMAGE;
        var taggedPrevious = localImage + ":" + PREVIOUS + suffix;
        docker("tag", image(PREVIOUS), taggedPrevious).expect(0);
        try {
            env.put("PROXY_AGENT_IMAGE", localImage);
            Files.writeString(directory.resolve("version"), PREVIOUS);
            manage(0, "start");
            assertThat(manage(1, "upgrade"), containsString("rolling back"));
            assertVersion(PREVIOUS);
            assertRunning(taggedPrevious);
        } finally {
            docker("rm", "-f", CONTAINER);
            docker("image", "rm", taggedPrevious);
        }
    }

    @Test
    public void failedReinstallsPreserveTheExistingFilesAndContainer() throws Exception {
        var rejected = install(null, "rejected-token", false);
        rejected.expect(1);
        assertThat(rejected.output(), containsString("HTTP 401"));
        assertThat(Files.exists(directory.resolve("config/config.json")), is(false));

        install(fixture("config.json"), null, false).expect(0);
        var existing = new LinkedHashMap<Path, byte[]>();
        for (var file : List.of("config/config.json", "version", "settings." + EXTENSION, MANAGER_SCRIPT,
                WINDOWS ? "manager.exe" : "manager")) {
            var path = directory.resolve(file);
            existing.put(path, Files.readAllBytes(path));
        }
        var id = dockerOutput("inspect", "-f", "{{.Id}}", CONTAINER);
        for (var invalid : List.of("{}", "{\"agent_id\":4}", "{\"agent_id\":\"\"}", "{\"agent_id\":\"other\"} trailing")) {
            responseOverride = invalid;
            install(null, TOKEN, false).expect(1);
        }
        responseOverride = "{\"agent_id\":\"other\"}";
        registryStatus = 503;
        assertThat(install(null, TOKEN, false).output(), containsString("Unable to query image registry"));
        registryStatus = 200;
        var missing = new LinkedHashMap<>(env);
        missing.put("TOKEN", TOKEN);
        missing.put("PROXY_AGENT_INSTALLER_BINARY", directory.resolve("missing-manager").toString());
        run(List.of(executablePath, "install", "--install-dir", directory.toString()), missing, 60).expect(1);
        for (var entry : existing.entrySet()) {
            assertThat(entry.getKey().toString(), Files.readAllBytes(entry.getKey()), is(entry.getValue()));
        }
        assertThat(dockerOutput("inspect", "-f", "{{.Id}}", CONTAINER), is(id));
        assertRunning(image(LATEST));
        try (var files = Files.walk(directory)) {
            assertThat(files.noneMatch(path -> path.toString().endsWith(".tmp")), is(true));
        }
    }

    private ProductTestSupport.Result install(Path path, String token, boolean override) throws Exception {
        var variables = new LinkedHashMap<>(env);
        if (token != null) {
            variables.put("TOKEN", token);
        }
        var arguments = new ArrayList<String>();
        if (path != null) {
            arguments.add(path.toString());
        }
        arguments.addAll(List.of(WINDOWS ? "-InstallDir" : "--install-dir", directory.toString()));
        if (override) {
            arguments.addAll(List.of("-WindowsVersion", windowsVersion));
        }
        return run(script(repository().resolve("install." + EXTENSION), arguments.toArray(String[]::new)), variables, 180);
    }

    private String manage(int status, String... arguments) throws Exception {
        var result = run(script(directory.resolve(MANAGER_SCRIPT), arguments), env, 180);
        result.expect(status);
        return result.output();
    }

    private void serve(HttpExchange request) throws IOException {
        var path = request.getRequestURI().getPath();
        switch (path) {
            case "/manager" -> send(request, 200, "application/octet-stream", executable);
            case "/v2/stub/tags/list" -> send(request, registryStatus, "application/json",
                    ("{\"tags\":[\"" + PREVIOUS + suffix + "\",\"" + LATEST + suffix + "\",\"9.0.0\"]}").getBytes(UTF_8));
            case "/proxy-agent/configure" -> {
                var authorized = request.getRequestMethod().equals("POST")
                        && ("Basic " + TOKEN).equals(request.getRequestHeaders().getFirst("Authorization"));
                var body = responseOverride == null ? config : responseOverride.getBytes(UTF_8);
                send(request, authorized ? 200 : 401, "application/json", authorized ? body : new byte[0]);
            }
            default -> {
                if (path.equals("/" + MANAGER_SCRIPT)) {
                    send(request, 200, "application/octet-stream", Files.readAllBytes(repository().resolve(MANAGER_SCRIPT)));
                } else {
                    send(request, 404, "text/plain", new byte[0]);
                }
            }
        }
    }

    private void assertVersion(String version) throws IOException {
        assertThat(Files.readString(directory.resolve("version")).strip(), is(version));
    }

    private static void assertRunning(String image) throws Exception {
        assertThat(dockerOutput("inspect", "-f", "{{.Config.Image}}", CONTAINER), is(image));
        assertThat(dockerOutput("inspect", "-f", "{{.State.Health.Status}}", CONTAINER), is("healthy"));
    }

    private static String dockerOutput(String... arguments) throws Exception {
        var result = docker(arguments);
        result.expect(0);
        return result.output().strip();
    }

    private static ProductTestSupport.Result docker(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        return run(command, environment(), 600);
    }

    private static String image(String version) {
        return IMAGE + ":" + version + suffix;
    }

    private static Path fixture(String name) {
        return repository().resolve("tests/integration/fixtures").resolve(name);
    }
}
