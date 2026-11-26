package com.fivetran.proxy_agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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

    private int invoke(String... command) {
        var arguments = new ArrayList<>(List.of("--agent-dir", directory.toString()));
        arguments.addAll(List.of(command));
        var operations = new UnixOperations(commands, docker);
        var manager = new Manager(operations, docker, new HttpClient(operations.proxySettings()));
        return new Application(manager, path -> 0).execute(arguments.toArray(String[]::new));
    }

    private static final class TestDocker extends Docker {
        private final List<List<String>> calls = new ArrayList<>();
        private int status;
        private boolean running;

        private TestDocker(CommandRunner commands) {
            super(commands);
        }

        @Override
        int run(Path directory, String... arguments) {
            calls.add(List.of(arguments));
            return status;
        }

        private List<String> operations() {
            return calls.stream().map(call -> call.get(0)).toList();
        }

        @Override
        CommandRunner.Result capture(String... arguments) {
            calls.add(List.of(arguments));
            return switch (arguments[0]) {
                case "inspect" -> new CommandRunner.Result(running ? 0 : 1, "");
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
