package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs Docker commands on the host. */
class Docker {
    private final CommandRunner commands;
    private final String executable;

    Docker(CommandRunner commands) {
        this(commands, "docker");
    }

    Docker(CommandRunner commands, String executable) {
        this.commands = commands;
        this.executable = executable;
    }

    CommandRunner.Result capture(String... arguments) throws IOException, InterruptedException {
        return commands.capture(command(arguments));
    }

    int run(Path directory, String... arguments) throws IOException, InterruptedException {
        return commands.run(directory, command(arguments));
    }

    int runAndRecord(Path directory, Path report, List<String> arguments) throws IOException, InterruptedException {
        return commands.runAndRecord(directory, command(arguments.toArray(String[]::new)), report);
    }

    private List<String> command(String[] arguments) {
        var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(List.of(arguments));
        return command;
    }
}
