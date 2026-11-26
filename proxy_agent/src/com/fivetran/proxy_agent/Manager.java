package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Command-line entry point for the host-side proxy agent manager. */
@CommandLine.Command(name = "manager", exitCodeOnInvalidInput = 1)
public final class Manager {
    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    private Manager() {}

    /** Runs the requested management command and returns its exit status to the caller. */
    public static void main(String[] args) {
        var commandLine = new CommandLine(new Manager());
        commandLine.addSubcommand("install", new InstallArguments());
        commandLine.setExpandAtFiles(false);
        commandLine.setExecutionStrategy(Manager::execute);
        System.exit(commandLine.execute(args));
    }

    private static int execute(CommandLine.ParseResult result) {
        var helpExitCode = CommandLine.executeHelpRequest(result);
        if (helpExitCode != null) {
            return helpExitCode;
        }
        var install = result.subcommand();
        if (install == null) {
            result.commandSpec().commandLine().usage(System.err);
            return 1;
        }
        var arguments = (InstallArguments) install.commandSpec().userObject();
        try {
            var operations = Operations.forCurrentHost();
            var proxy = ProxySettings.initialize(operations.isWindows());
            var http = new HttpClient(proxy);
            new Installer(operations, http).install(arguments.config, arguments.installDir, arguments.windowsVersion);
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: Installation interrupted.");
        }
        return 1;
    }

    /** Options and the optional configuration path accepted by the install subcommand. */
    @CommandLine.Command(
            name = "install",
            description = "Install and start the Fivetran Proxy Agent.",
            exitCodeOnInvalidInput = 1)
    static final class InstallArguments {
        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
        private boolean help;

        @CommandLine.Option(names = "--install-dir", paramLabel = "DIR", description = "Installation directory.")
        private @Nullable Path installDir;

        @CommandLine.Option(names = "--windows-version", paramLabel = "LTSC",
                description = "Windows container image variant.")
        private String windowsVersion = "";

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "CONFIG",
                description = "Configuration file. Use TOKEN to fetch one instead.")
        private @Nullable Path config;
    }
}
