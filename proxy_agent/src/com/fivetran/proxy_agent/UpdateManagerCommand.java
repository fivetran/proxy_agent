package com.fivetran.proxy_agent;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Updates the native manager without restarting Docker. */
@CommandLine.Command(name = "update-manager", description = "Update the native manager.",
        exitCodeOnInvalidInput = 1)
final class UpdateManagerCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private @Nullable Application application;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    @Override
    public Integer call() {
        var app = Objects.requireNonNull(application);
        try {
            return app.manager().updateManager(app.agentDirectory());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
        }
        return 1;
    }
}
