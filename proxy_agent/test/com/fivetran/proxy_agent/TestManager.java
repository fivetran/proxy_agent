package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Runs the production installer with isolated disk and Windows host probes for Python tests. */
public final class TestManager {
    public static void main(String[] args) {
        var commands = new CommandRunner(Set.of("TOKEN"));
        var operations = OsOperations.forCurrentHost(commands);
        if (operations instanceof WindowsOperations) {
            operations = new WindowsOperations(commands) {
                @Override
                CommandRunner.Result runPowerShell(String command, String argument)
                        throws IOException, InterruptedException {
                    var stubs = """
                            function Get-CimInstance {
                                ConvertTo-Json -InputObject (@('Get-CimInstance') + @($args)) -Compress |
                                    & $env:AGENT_TEST_PYTHON $env:AGENT_TEST_STUB native |
                                    Out-String | Invoke-Expression
                            }
                            """;
                    return super.runPowerShell(stubs + command, argument);
                }
            };
        }
        operations.configureProxy();
        var manager = new Manager(operations, commands, new HttpClient());
        System.exit(new Application(manager, TestManager::diskSpace).execute(args));
    }

    private static long diskSpace(Path path) throws IOException {
        var root = Path.of(System.getenv("AGENT_TEST_ROOT"));
        Files.writeString(root.resolve("disk-path"), path.toString());
        return Long.parseLong(Files.readString(root.resolve("free-mb")));
    }
}
