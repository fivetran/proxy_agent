package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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

    /** Streams combined process output and saves a copy without hiding the process exit code. */
    int runAndRecord(Path directory, List<String> command, Path report) throws IOException, InterruptedException {
        try (var file = Files.newOutputStream(report)) {
            var process = builder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            try (var output = process.getInputStream()) {
                process.getOutputStream().close();
                copyOutput(output, file);
                return process.waitFor();
            } finally {
                process.destroy();
            }
        }
    }

    private static void copyOutput(InputStream output, OutputStream file) throws IOException {
        var buffer = new byte[8192];
        var saveReport = true;
        for (int read; (read = output.read(buffer)) != -1; ) {
            System.out.write(buffer, 0, read);
            System.out.flush();
            if (saveReport) {
                try {
                    file.write(buffer, 0, read);
                } catch (IOException e) {
                    saveReport = false;
                    System.err.println("WARNING: Could not save the entire command report.");
                }
            }
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
