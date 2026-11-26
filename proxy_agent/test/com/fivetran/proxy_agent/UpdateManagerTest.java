package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises downloaded binary verification and script validation against a local release. */
public final class UpdateManagerTest {
    private static final byte[] MANAGER = "verified native manager".getBytes(UTF_8);
    private static final String SCRIPT = "# new manager script\n";

    @TempDir
    Path temporary;

    private final CommandRunner commands = new CommandRunner(Set.of());
    private final OsOperations operations = OsOperations.forCurrentHost(commands, new Docker(commands));
    private Path directory;
    private Path manager;
    private Path wrapper;

    @BeforeEach
    public void createExistingInstallation() throws IOException {
        directory = temporary;
        manager = directory.resolve(operations.managerBinaryName());
        wrapper = directory.resolve(operations.managerScriptName());
        Files.writeString(manager, "original manager");
        Files.writeString(wrapper, "original script");
    }

    @Test
    public void updatesTheBinaryAndScriptTogetherAfterVerification() throws Exception {
        assertThat(run(SCRIPT, hash()), is(0));
        assertThat(Files.readAllBytes(manager), is(MANAGER));
        assertThat(Files.readString(wrapper), is(SCRIPT));
        assertStagingCleaned();
    }

    @Test
    public void preservesBothFilesWhenTheChecksumDoesNotMatch() throws Exception {
        var failure = assertThrows(IOException.class, () -> run(SCRIPT, "0".repeat(64)));
        assertThat(failure.getMessage(), containsString("SHA-256 does not match"));
        assertOriginalFiles();
    }

    @Test
    public void preservesBothFilesWhenTheScriptHasInvalidSyntax() throws Exception {
        var failure = assertThrows(IOException.class, () -> run("if (", hash()));
        assertThat(failure.getMessage(), containsString("invalid syntax"));
        assertOriginalFiles();
    }

    private int run(String script, String digest) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            try (request) {
                var body = request.getRequestURI().getPath().equals("/manager") ? MANAGER : script.getBytes(UTF_8);
                request.sendResponseHeaders(200, body.length);
                request.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new HttpClient(operations.proxySettings());
            return new ManagerUpdate(operations, client).run(directory, url + "/manager", digest, url + "/script");
        } finally {
            server.stop(0);
        }
    }

    private static String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MANAGER));
    }

    private void assertOriginalFiles() throws IOException {
        assertThat(Files.readString(manager), is("original manager"));
        assertThat(Files.readString(wrapper), is("original script"));
        assertStagingCleaned();
    }

    private void assertStagingCleaned() throws IOException {
        try (var entries = Files.list(directory)) {
            assertThat(entries.count(), is(2L));
        }
    }
}
