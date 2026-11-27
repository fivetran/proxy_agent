package com.fivetran.proxy_agent;

import java.io.IOException;
import java.util.List;
import picocli.CommandLine;

/** Run proxy agent diagnostics. */
@CommandLine.Command(name = "doctor", description = "Run proxy agent diagnostics.", exitCodeOnInvalidInput = 1)
final class DoctorCommand extends AgentCommand {
    @CommandLine.Parameters(arity = "0..*", paramLabel = "DOCTOR_ARG")
    private List<String> arguments = List.of();

    @Override
    int run(Application application) throws IOException, InterruptedException {
        return application.manager().doctor(application.agentDirectory(), arguments);
    }
}
