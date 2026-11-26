package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Runs host tools and preserves the existing curl and Windows proxy behavior. */
final class InstallHost {
    private static final String WINDOWS_HELPER = "/com/fivetran/proxy_agent/windows-installer.ps1";
    private static final String DOCKER = "docker";
    private static final String TOKEN = "TOKEN";

    final boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    private final String powerShell = env("PROXY_AGENT_INSTALLER_POWERSHELL", "powershell.exe");

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

    boolean hasCommand(String name) {
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

    Result windows(String operation, String argument, String value) throws IOException, InterruptedException {
        var script = Objects.requireNonNull(InstallHost.class.getResourceAsStream(WINDOWS_HELPER));
        String encoded;
        try (script) {
            encoded = Base64.getEncoder().encodeToString(new String(script.readAllBytes(), UTF_8).getBytes(UTF_16LE));
        }
        var process = builder(List.of(powerShell, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        process.environment().put("PROXY_AGENT_INSTALL_OPERATION", operation);
        process.environment().put("PROXY_AGENT_INSTALL_ARGUMENT", argument);
        process.environment().put("PROXY_AGENT_INSTALL_VALUE", value);
        if (operation.equals("configure") || operation.equals("download") || operation.equals("tags")) {
            // The adapter emits only proxy diagnostics with credentials removed on stderr.
            process.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        return capture(process, null);
    }

    void download(String url, Path target) throws IOException, InterruptedException {
        var result = isWindows
                ? windows("download", url, target.toString())
                : capture("curl", "-fSsL", "--max-time", "30", "--url", url, "-o", target.toString());
        if (!result.succeeded()) {
            throw new IOException("Failed to download management script");
        }
    }

    String getTags(String url) throws IOException, InterruptedException {
        var result = isWindows
                ? windows("tags", url, "")
                : capture("curl", "-s", "-f", "--max-time", "30", "--url", url);
        if (!result.succeeded()) {
            throw new IOException("Unable to query image registry for latest version");
        }
        return result.output();
    }

    byte[] configure(String url, String token) throws IOException, InterruptedException {
        Result result;
        String status;
        String body;
        if (isWindows) {
            result = windows("configure", url, token);
            var endOfStatus = result.output().indexOf('\n');
            if (result.status() == 2) {
                throw new IOException("Configure endpoint returned an invalid JSON response");
            }
            if (endOfStatus < 0) {
                throw new IOException("Failed to connect to configure endpoint");
            }
            status = result.output().substring(0, endOfStatus).strip();
            body = result.output().substring(endOfStatus + 1);
        } else {
            if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IOException("Invalid TOKEN");
            }
            var process = builder(List.of(
                    "curl", "-sS", "--max-time", "30", "-w", "\\n%{http_code}",
                    "-X", "POST", "-H", "@-", "-H", "Accept: application/json", "--url", url));
            result = capture(process, "Authorization: Basic " + token + "\n");
            var startOfStatus = result.output().stripTrailing().lastIndexOf('\n');
            if (startOfStatus < 0) {
                throw new IOException("Failed to connect to configure endpoint");
            }
            status = result.output().substring(startOfStatus + 1).strip();
            body = result.output().substring(0, startOfStatus);
        }
        if (!result.succeeded()) {
            throw new IOException("Failed to connect to configure endpoint");
        }
        if (status.equals("401") && isWindows) {
            throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
        }
        if (!status.equals("200")) {
            throw new IOException("Configure endpoint returned HTTP " + (status.matches("[0-9]{3}") ? status : "unknown"));
        }
        return body.getBytes(UTF_8);
    }

    void start(Path manager) throws IOException, InterruptedException {
        var command = isWindows
                ? List.of(powerShell, "-NoProfile", "-File", manager.toString(), "start")
                : List.of(manager.toString(), "start");
        var process = builder(command).directory(manager.getParent().toFile()).inheritIO().start();
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Installation complete, but agent failed to start.");
            }
        } finally {
            process.destroy();
        }
    }

    private static ProcessBuilder builder(List<String> command) {
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().remove(TOKEN);
        return builder;
    }

    private static Result capture(ProcessBuilder builder, @Nullable String input)
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
