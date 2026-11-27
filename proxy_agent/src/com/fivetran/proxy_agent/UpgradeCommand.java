package com.fivetran.proxy_agent;

import java.io.IOException;
import picocli.CommandLine;

/** Upgrade to the latest compatible image. */
@CommandLine.Command(name = "upgrade", description = "Upgrade to the latest compatible image.", exitCodeOnInvalidInput = 1)
final class UpgradeCommand extends AgentCommand {
    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().upgrade(application.agentDirectory());
    }
}
