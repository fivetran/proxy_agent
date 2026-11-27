package com.fivetran.proxy_agent;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Command-line entry point for the host-side proxy agent manager. */
@CommandLine.Command(name = "manager", subcommands = InstallCommand.class, exitCodeOnInvalidInput = 1)
public final class Application implements Callable<Integer> {
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    @CommandLine.Spec
    private CommandLine.Model.@Nullable CommandSpec spec;

    private final Manager manager;

    private Application() {
        var commands = new CommandRunner(Set.of("TOKEN"));
        var operations = OsOperations.forCurrentHost(commands);
        operations.configureProxy();
        this.manager = new Manager(operations, commands, new HttpClient());
    }

    /** Runs the requested management command and returns its exit status to the caller. */
    public static void main(String[] args) {
        try {
            var commandLine = new CommandLine(new Application());
            commandLine.setExpandAtFiles(false);
            System.exit(commandLine.execute(args));
        } catch (ProxySettings.ConfigurationException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    @Override
    public Integer call() {
        Objects.requireNonNull(spec).commandLine().usage(System.err);
        return 1;
    }

    Manager manager() {
        return manager;
    }
}
