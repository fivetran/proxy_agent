package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Runs local processes with a caller-specified environment filter. */
final class CommandRunner {
    private final Set<String> excludedEnvironment;

    CommandRunner(Set<String> excludedEnvironment) {
        this.excludedEnvironment = Set.copyOf(excludedEnvironment);
    }

    Result capture(String... command) throws IOException, InterruptedException {
        return capture(List.of(command));
    }

    Result capture(List<String> command) throws IOException, InterruptedException {
        return capture(builder(command));
    }

    int run(Path directory, List<String> command) throws IOException, InterruptedException {
        var process = builder(command).directory(directory.toFile()).inheritIO().start();
        try {
            return process.waitFor();
        } finally {
            process.destroy();
        }
    }

    ProcessBuilder builder(List<String> command) {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        excludedEnvironment.forEach(builder.environment()::remove);
        return builder;
    }

    Result capture(ProcessBuilder builder) throws IOException, InterruptedException {
        var process = builder.start();
        try (var stdout = process.getInputStream()) {
            process.getOutputStream().close();
            var output = new String(stdout.readAllBytes(), UTF_8);
            return new Result(process.waitFor(), output);
        } finally {
            process.destroy();
        }
    }

    /** Captured command output and its process exit code. */
    record Result(int status, String output) {
        boolean succeeded() {
            return status == 0;
        }
    }
}
