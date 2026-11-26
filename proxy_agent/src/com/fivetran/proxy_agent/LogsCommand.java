package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Follow the agent's Docker logs. */
@CommandLine.Command(name = "logs", description = "Follow the agent's Docker logs.", exitCodeOnInvalidInput = 1)
final class LogsCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().logs(application.agentDirectory());
    }
}
