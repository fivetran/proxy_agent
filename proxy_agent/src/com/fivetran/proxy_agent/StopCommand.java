package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Stop and remove the agent container. */
@CommandLine.Command(name = "stop", description = "Stop and remove the agent container.", exitCodeOnInvalidInput = 1)
final class StopCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().stop(application.agentDirectory());
    }
}
