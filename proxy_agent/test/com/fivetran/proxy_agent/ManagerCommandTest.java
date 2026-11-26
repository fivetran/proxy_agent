package com.fivetran.proxy_agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the production command line with controlled host dependencies. */
public final class ManagerCommandTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private final CommandRunner commands = new CommandRunner(Set.of());
    private final TestDocker docker = new TestDocker(commands);
    private String memory = "4096";
    private OsOperations operations = new TestUnixOperations();
    private final TestHttpClient http = new TestHttpClient();
    private Path directory;
    private Path config;
    private Path version;

    @Before
    public void createInstallation() throws Exception {
        directory = temporary.newFolder("proxy agent").toPath();
        config = Files.createDirectories(directory.resolve("config")).resolve("config.json");
        Files.writeString(config, "{\"agent_id\":\"abc123\"}");
        version = directory.resolve("version");
        Files.writeString(version, "1.2.9\n");
    }

    @Test
    public void statusFiltersDockerForTheInstalledAgent() throws Exception {
        Files.writeString(version, "invalid version");
        assertThat(invoke("status"), is(0));
        assertThat(docker.calls, is(List.of(List.of("ps", "-a", "--filter", "name=proxy-agent-abc123",
                "--format", "table {{.Names}}\\t{{.Image}}\\t{{.Status}}"))));
    }

    @Test
    public void statusRejectsMissingConfigurationBeforeRunningDocker() throws Exception {
        Files.delete(config);
        assertThat(invoke("status"), is(1));
        assertThat(docker.calls, is(empty()));
    }

    @Test
    public void logsFollowsTheConfiguredContainerAndReturnsTheDockerStatus() {
        docker.status = 7;
        assertThat(invoke("logs"), is(7));
        assertThat(docker.calls, is(List.of(List.of("logs", "-f", "proxy-agent-abc123"))));
    }

    @Test
    public void stopRemovesAnExistingContainer() throws Exception {
        docker.running = true;
        assertThat(invoke("stop"), is(0));
        assertThat(docker.operations(), is(List.of("inspect", "stop", "rm")));
        assertThat(docker.running, is(false));
        var log = Files.readString(directory.resolve("logs/proxy-agent-manager.log"));
        assertThat(log, containsString("Stopping proxy-agent-abc123"));
        assertThat(log, containsString("Stopped proxy-agent-abc123"));
    }

    @Test
    public void stopSucceedsWhenTheContainerIsAlreadyAbsent() throws Exception {
        assertThat(invoke("stop"), is(0));
        assertThat(docker.operations(), is(List.of("inspect")));
        var log = Files.readString(directory.resolve("logs/proxy-agent-manager.log"));
        assertThat(log, containsString("Stopping proxy-agent-abc123"));
        assertThat(log, not(containsString("Stopped proxy-agent-abc123")));
    }

    @Test
    public void startNormalizesLegacyPinsAndUsesMemoryAndHeartbeatSettings() throws Exception {
        Files.writeString(version, "1.2.9-ubuntu-26.04");
        assertThat(invoke("start"), is(0));
        assertThat(Files.readString(version).strip(), is("1.2.9"));
        assertThat(docker.lastRun, hasItems("--memory=4096m", "HEARTBEAT_PATH=/tmp/proxy-agent-heartbeat.txt",
                "-i", "/config/config.json"));
        assertThat(docker.lastRun, hasItem(endsWith(":1.2.9-ubuntu-26.04")));
        assertThat(docker.operations(), is(List.of("inspect", "run", "inspect", "ps")));
    }

    @Test
    public void startWithInvalidMemoryLeavesTheRunningContainerAlone() {
        memory = "0";
        docker.running = true;
        assertThat(invoke("start"), is(1));
        assertThat(docker.calls, is(empty()));
        assertThat(docker.running, is(true));
    }

    @Test
    public void startMigratesAWindowsImageTagAndMountsTheConfigDirectory() throws Exception {
        Files.writeString(version, "1.2.9-windows-ltsc2022");
        operations = new WindowsOperations(commands, docker) {
            @Override
            public String containerOs(List<String> errors) {
                return "windows";
            }

            @Override
            public ContainerSettings containerSettings(Path path) {
                return new ContainerSettings(memory, "");
            }
        };
        assertThat(invoke("start"), is(0));
        assertThat(Files.readString(version).strip(), is("1.2.9"));
        assertThat(Files.readString(directory.resolve("settings.ps1")), containsString("$WINDOWS_VERSION = 'ltsc2022'"));
        assertThat(docker.lastRun, hasItems("HEARTBEAT_PATH=C:/app/logs/proxy-agent-heartbeat.txt",
                directory.resolve("config").toString().replace('\\', '/') + ":C:/config:ro"));
        assertThat(docker.lastRun, hasItem(endsWith(":1.2.9-windows-ltsc2022")));
    }

    private class TestUnixOperations extends UnixOperations {
        private TestUnixOperations() {
            super(commands, docker);
        }

        @Override
        public ContainerSettings containerSettings(Path path) {
            return new ContainerSettings(memory, "");
        }
    }

    @Test
    public void restartStopsAndRemovesTheOldContainerOnce() {
        docker.running = true;
        assertThat(invoke("restart"), is(0));
        assertThat(docker.operations(), is(List.of("inspect", "stop", "rm", "run", "inspect", "ps")));
        assertThat(docker.lastRun, hasItem(endsWith(":1.2.9-ubuntu-26.04")));
    }

    @Test
    public void upgradeSelectsTheHighestCompatibleVersion() throws Exception {
        docker.running = true;
        assertThat(invoke("upgrade"), is(0));
        assertThat(Files.readString(version).strip(), is("1.2.10"));
        assertThat(docker.lastRun, hasItem(endsWith(":1.2.10-ubuntu-26.04")));
    }

    @Test
    public void upgradeRestoresThePreviousVersionAfterStartupFails() throws Exception {
        docker.running = true;
        docker.failImage = ":1.2.10-ubuntu-26.04";
        assertThat(invoke("upgrade"), is(1));
        assertThat(Files.readString(version).strip(), is("1.2.9"));
        assertThat(docker.lastRun, hasItem(endsWith(":1.2.9-ubuntu-26.04")));
        assertThat(docker.running, is(true));
    }

    @Test
    public void upgradeRegistryFailureLeavesTheRunningAgentAlone() throws Exception {
        docker.running = true;
        http.registryAvailable = false;
        assertThat(invoke("upgrade"), is(1));
        assertThat(Files.readString(version).strip(), is("1.2.9"));
        assertThat(docker.calls, is(empty()));
        assertThat(docker.running, is(true));
    }

    private static final class TestHttpClient extends HttpClient {
        private boolean registryAvailable = true;

        private TestHttpClient() {
            super(ProxySettings.forUnix());
        }

        @Override
        byte[] get(String url) throws IOException {
            if (!registryAvailable) {
                throw new IOException("registry unavailable");
            }
            return """
                    {"tags":["1.2.10-ubuntu-26.04","1.2.8-ubuntu-26.04","9.2.0-windows-ltsc2022"]}
                    """.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    public void doctorForwardsArgumentsAndExitStatusAndSavesOutput() throws Exception {
        docker.running = true;
        assertThat(invoke("doctor", "--", "--region", "us-east-1"), is(7));
        assertThat(docker.lastRun.subList(docker.lastRun.size() - 3, docker.lastRun.size()),
                is(List.of("--doctor", "--region", "us-east-1")));
        assertThat(docker.running, is(true));
        try (var reports = Files.list(directory.resolve("logs"))) {
            var report = reports.findFirst().orElseThrow();
            assertThat(report.getFileName().toString(), startsWith("proxy-agent-manager-doctor-"));
            assertThat(Files.readString(report), containsString("doctor output"));
        }
    }

    @Test
    public void doctorKeepsOnlyTheContainersOwnReportWhenItExists() throws Exception {
        docker.doctorWritesReport = true;
        assertThat(invoke("doctor"), is(7));
        try (var reports = Files.list(directory.resolve("logs"))) {
            assertThat(reports.map(path -> path.getFileName().toString()).toList(), is(List.of("doctor-stub.log")));
        }
    }

    private int invoke(String... command) {
        var arguments = new ArrayList<>(List.of("--agent-dir", directory.toString()));
        arguments.addAll(List.of(command));
        var manager = new Manager(operations, docker, http);
        return new Application(manager, path -> 0).execute(arguments.toArray(String[]::new));
    }

    private static final class TestDocker extends Docker {
        private final List<List<String>> calls = new ArrayList<>();
        private int status;
        private boolean running;
        private boolean doctorWritesReport;
        private List<String> lastRun = List.of();
        private String failImage = "";

        private TestDocker(CommandRunner commands) {
            super(commands);
        }

        @Override
        int run(Path directory, String... arguments) {
            calls.add(List.of(arguments));
            if (arguments[0].equals("run")) {
                lastRun = List.of(arguments);
                if (!failImage.isEmpty() && lastRun.stream().anyMatch(arg -> arg.endsWith(failImage))) {
                    return 125;
                }
                running = true;
            }
            return status;
        }

        @Override
        int runAndRecord(Path path, Path report, List<String> arguments) throws IOException {
            lastRun = List.copyOf(arguments);
            Files.writeString(report, "doctor output");
            if (doctorWritesReport) {
                Files.writeString(report.resolveSibling("doctor-stub.log"), "container report");
            }
            return 7;
        }

        private List<String> operations() {
            return calls.stream().map(call -> call.get(0)).toList();
        }

        @Override
        CommandRunner.Result capture(String... arguments) {
            calls.add(List.of(arguments));
            return switch (arguments[0]) {
                case "inspect" -> new CommandRunner.Result(running ? 0 : 1, arguments.length > 2 ? "healthy" : "");
                case "stop" -> new CommandRunner.Result(0, "");
                case "rm" -> {
                    running = false;
                    yield new CommandRunner.Result(0, "");
                }
                default -> throw new AssertionError("Unexpected Docker probe " + List.of(arguments));
            };
        }
    }
}
