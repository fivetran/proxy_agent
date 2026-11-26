package com.fivetran.proxy_agent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Command-line entry point for the host-side proxy agent manager. */
@CommandLine.Command(name = "manager", subcommands = {InstallCommand.class, UpdateManagerCommand.class},
        exitCodeOnInvalidInput = 1)
public final class Application implements Callable<Integer> {
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    @CommandLine.Spec
    private CommandLine.Model.@Nullable CommandSpec spec;

    @CommandLine.Option(names = "--agent-dir", paramLabel = "DIR", description = "Existing installation directory.")
    private @Nullable Path agentDirectory;

    private final Manager manager;
    private final Installer installer;

    Application(Manager manager, Installer.DiskSpace diskSpace) {
        this.manager = manager;
        this.installer = new Installer(manager, diskSpace);
    }

    /** Runs the requested management command and returns its exit status to the caller. */
    public static void main(String[] args) {
        var commands = new CommandRunner(Set.of("TOKEN"));
        var operations = OsOperations.forCurrentHost(commands);
        try {
            operations.configureProxy();
        } catch (ProxySettings.ConfigurationException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
            return;
        }
        var manager = new Manager(operations, commands, new HttpClient());
        System.exit(new Application(manager, Installer.DiskSpace::fileStore).execute(args));
    }

    int execute(String[] args) {
        var commandLine = new CommandLine(this);
        commandLine.setExpandAtFiles(false);
        return commandLine.execute(args);
    }

    @Override
    public Integer call() {
        Objects.requireNonNull(spec).commandLine().usage(System.err);
        return 1;
    }

    Manager manager() {
        return manager;
    }

    /** Uses the native executable's directory unless the caller selects an installation. */
    Path agentDirectory() {
        if (agentDirectory != null) {
            return agentDirectory.toAbsolutePath().normalize();
        }
        if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return ProcessHandle.current().info().command()
                    .map(command -> Path.of(command).toAbsolutePath().getParent())
                    .orElseGet(() -> Path.of("").toAbsolutePath());
        }
        return Path.of("").toAbsolutePath();
    }

    Installer installer() {
        return installer;
    }
}
