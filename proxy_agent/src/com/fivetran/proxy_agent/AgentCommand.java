package com.fivetran.proxy_agent;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Supplies the installed agent and standard CLI error handling to a management command. */
abstract class AgentCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private @Nullable Application application;

    @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "Show usage.")
    private boolean help;

    abstract int run(Application application) throws IOException, InterruptedException;

    @Override
    public final Integer call() {
        try {
            return run(Objects.requireNonNull(application));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("ERROR: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: Command interrupted.");
        }
        return 1;
    }
}
