package com.fivetran.proxy_agent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

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

    private int invoke(String... command) {
        var arguments = new ArrayList<>(List.of("--agent-dir", directory.toString()));
        arguments.addAll(List.of(command));
        var operations = new UnixOperations(commands, docker);
        var manager = new Manager(operations, docker, new HttpClient(operations.proxySettings()));
        return new Application(manager, path -> 0).execute(arguments.toArray(String[]::new));
    }

    private static final class TestDocker extends Docker {
        private final List<List<String>> calls = new ArrayList<>();

        private TestDocker(CommandRunner commands) {
            super(commands);
        }

        @Override
        int run(Path directory, String... arguments) {
            calls.add(List.of(arguments));
            return 0;
        }

        @Override
        CommandRunner.Result capture(String... arguments) {
            throw new AssertionError("Unexpected Docker probe " + List.of(arguments));
        }
    }
}
