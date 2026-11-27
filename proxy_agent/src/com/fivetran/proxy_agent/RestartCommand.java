package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Restart the pinned proxy agent container. */
@CommandLine.Command(name = "restart", description = "Restart the pinned proxy agent container.", exitCodeOnInvalidInput = 1)
final class RestartCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().restart(application.agentDirectory());
    }
}
