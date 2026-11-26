package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Shows the installed agent's Docker status. */
@CommandLine.Command(name = "status", description = "Show the agent's Docker status.", exitCodeOnInvalidInput = 1)
final class StatusCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().status(application.agentDirectory());
    }
}
