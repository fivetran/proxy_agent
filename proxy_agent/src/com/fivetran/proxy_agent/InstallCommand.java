package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Installs and starts a proxy agent from the command line. */
@CommandLine.Command(name = "install", description = "Install and start the Fivetran Proxy Agent.",
        exitCodeOnInvalidInput = 1)
final class InstallCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private @Nullable Application application;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    @CommandLine.Option(names = "--install-dir", paramLabel = "DIR", description = "Installation directory.")
    private @Nullable Path installDir;

    @CommandLine.Option(names = "--windows-version", paramLabel = "LTSC", description = "Windows container image variant.")
    private String windowsVersion = "";

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "CONFIG",
            description = "Configuration file. Use TOKEN to fetch one instead.")
    private @Nullable Path config;

    @Override
    public Integer call() {
        try {
            new Installer(Objects.requireNonNull(application).manager()).install(config, installDir, windowsVersion);
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
