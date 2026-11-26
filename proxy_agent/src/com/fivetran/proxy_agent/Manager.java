package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParseResult;

/** Command-line entry point for the host-side proxy agent manager. */
public final class Manager {
    private static final String INSTALL_DIR = "--install-dir";
    private static final String WINDOWS_VERSION = "--windows-version";

    private Manager() {}

    /** Runs the requested management command and returns its exit status to the caller. */
    public static void main(String[] args) {
        var install = CommandSpec.create().name("install").exitCodeOnInvalidInput(1);
        install.usageMessage().description("Install and start the Fivetran Proxy Agent.");
        install.addOption(helpOption());
        install.addOption(OptionSpec.builder(INSTALL_DIR)
                .type(Path.class).paramLabel("DIR").description("Installation directory.").build());
        install.addOption(OptionSpec.builder(WINDOWS_VERSION)
                .type(String.class).paramLabel("LTSC").description("Windows container image variant.").build());
        install.addPositional(PositionalParamSpec.builder()
                .index("0").arity("0..1").type(Path.class).paramLabel("CONFIG")
                .description("Configuration file. Use TOKEN to fetch one instead.").build());

        var manager = CommandSpec.create().name("manager").exitCodeOnInvalidInput(1);
        manager.addOption(helpOption());
        manager.addSubcommand("install", install);
        var commandLine = new CommandLine(manager);
        commandLine.setExpandAtFiles(false);
        commandLine.setExecutionStrategy(Manager::execute);
        System.exit(commandLine.execute(args));
    }

    private static OptionSpec helpOption() {
        return OptionSpec.builder("-h", "--help").usageHelp(true).description("Show usage.").build();
    }

    private static int execute(ParseResult result) {
        var helpExitCode = CommandLine.executeHelpRequest(result);
        if (helpExitCode != null) {
            return helpExitCode;
        }
        var install = result.subcommand();
        if (install == null) {
            result.commandSpec().commandLine().usage(System.err);
            return 1;
        }
        try {
            var operations = Operations.forCurrentHost();
            var proxy = ProxySettings.initialize(operations.isWindows());
            var http = new HttpClient(proxy);
            new Installer(operations, http).install(
                    install.matchedPositionalValue(0, null),
                    install.matchedOptionValue(INSTALL_DIR, null),
                    install.matchedOptionValue(WINDOWS_VERSION, ""));
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: Installation interrupted.");
        }
        return 1;
    }
}
