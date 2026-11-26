package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Start the pinned proxy agent container. */
@CommandLine.Command(name = "start", description = "Start the pinned proxy agent container.", exitCodeOnInvalidInput = 1)
final class StartCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().start(application.agentDirectory());
    }
}
