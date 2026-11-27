package com.fivetran.proxy_agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the production command line with controlled host dependencies. */
public final class ManagerCommandTest {
    @TempDir
    Path temporary;

    private final CommandRunner commands = new CommandRunner(Set.of());
    private final TestDocker docker = new TestDocker(commands);
    private Path directory;
    private Path config;
    private Path version;

    @BeforeEach
    public void createInstallation() throws Exception {
        directory = Files.createDirectory(temporary.resolve("proxy agent"));
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

    private int invoke(String... command) {
        var arguments = new ArrayList<>(List.of("--agent-dir", directory.toString()));
        arguments.addAll(List.of(command));
        var operations = new UnixOperations(commands, docker);
        var manager = new Manager(operations, docker, new HttpClient());
        return new Application(manager, path -> 0).execute(arguments.toArray(String[]::new));
    }

    private static final class TestDocker extends Docker {
        private final List<List<String>> calls = new ArrayList<>();
        private int status;

        private TestDocker(CommandRunner commands) {
            super(commands);
        }

        @Override
        int run(Path directory, String... arguments) {
            calls.add(List.of(arguments));
            return status;
        }

        @Override
        CommandRunner.Result capture(String... arguments) {
            throw new AssertionError("Unexpected Docker probe " + List.of(arguments));
        }
    }
}
