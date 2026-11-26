package com.fivetran.proxy_agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Runs Docker commands on the host. */
final class Docker {
    private final CommandRunner commands;

    Docker(CommandRunner commands) {
        this.commands = commands;
    }

    CommandRunner.Result capture(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("docker");
        command.addAll(List.of(arguments));
        return commands.capture(command);
    }
}
