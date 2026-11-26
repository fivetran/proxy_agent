package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Launches local commands and keeps installation credentials out of unrelated processes. */
final class InstallHost {
    private static final String DOCKER = "docker";
    private static final String TOKEN = "TOKEN";

    static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    Result docker(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add(DOCKER);
        command.addAll(List.of(arguments));
        return capture(command);
    }

    boolean hasCommand(String name, boolean isWindows) {
        var path = env("PATH", "");
        for (var directory : path.split(Pattern.quote(File.pathSeparator))) {
            var candidate = Path.of(directory, name + (isWindows ? ".exe" : ""));
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    Result capture(String... command) throws IOException, InterruptedException {
        return capture(List.of(command));
    }

    private Result capture(List<String> command) throws IOException, InterruptedException {
        return capture(builder(command), null);
    }

    void start(Path manager, List<String> command) throws IOException, InterruptedException {
        var process = builder(command).directory(manager.getParent().toFile()).inheritIO().start();
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Installation complete, but agent failed to start.");
            }
        } finally {
            process.destroy();
        }
    }

    static long parseSize(String size, long fallback) {
        try {
            return Long.parseLong(size);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static ProcessBuilder builder(List<String> command) {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove(TOKEN);
        return builder;
    }

    static Result capture(ProcessBuilder builder, @Nullable String input)
            throws IOException, InterruptedException {
        var process = builder.start();
        try (var stdout = process.getInputStream()) {
            try (var stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input.getBytes(UTF_8));
                }
            }
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
