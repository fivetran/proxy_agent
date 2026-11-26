package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the installed product's process and HTTP boundaries from the Java test suite. */
final class ProductTestSupport {
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final String EXTENSION = WINDOWS ? "ps1" : "sh";
    static final String MANAGER_SCRIPT = "proxy-agent-manager." + EXTENSION;
    static final String SHELL = System.getenv().getOrDefault("PROXY_AGENT_TEST_SHELL", WINDOWS ? "powershell" : "bash");

    private ProductTestSupport() {}

    static Path repository() {
        var explicit = System.getenv("PROXY_AGENT_TEST_REPOSITORY");
        if (explicit == null) {
            throw new AssertionError("Pass the checkout path via PROXY_AGENT_TEST_REPOSITORY.");
        }
        return Path.of(explicit);
    }

    static Map<String, String> environment() {
        var environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(key -> {
            var upper = key.toUpperCase(Locale.ROOT);
            return upper.endsWith("_PROXY") || upper.startsWith("PROXY_AGENT_") || upper.equals("TOKEN")
                    || upper.equals("PSMODULEPATH") || upper.equals("BASH_ENV") || upper.equals("ENV");
        });
        return environment;
    }

    static List<String> script(Path path, String... arguments) {
        var command = new ArrayList<>(WINDOWS
                ? List.of(SHELL, "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File")
                : List.of(SHELL));
        command.add(path.toString());
        command.addAll(List.of(arguments));
        return command;
    }

    static Result run(List<String> command, Map<String, String> environment, long seconds)
            throws IOException, InterruptedException {
        var output = Files.createTempFile("proxy-agent-test-output", ".log");
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            var process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out running " + command.get(0) + "\n" + Files.readString(output));
                }
                return new Result(process.exitValue(), new String(Files.readAllBytes(output), UTF_8));
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly().waitFor();
                }
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static void send(HttpExchange request, int code, String contentType, byte[] content) throws IOException {
        try (request) {
            request.getResponseHeaders().set("Content-Type", contentType);
            request.sendResponseHeaders(code, content.length);
            request.getResponseBody().write(content);
        }
    }

    /** Loopback endpoints for test assets and registry responses. */
    static final class Server implements AutoCloseable {
        private final HttpServer server;

        Server(String host, HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(host, 0), 0);
            server.createContext("/", handler);
            server.start();
        }

        String url() {
            return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    record Result(int status, String output) {
        void expect(int code) {
            assertThat(output, status, is(code));
        }
    }
}
