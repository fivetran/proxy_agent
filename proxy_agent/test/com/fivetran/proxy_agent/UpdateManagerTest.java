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

/** Exercises downloaded binary verification against a local release. */
public final class UpdateManagerTest {
    private static final byte[] MANAGER = "verified native manager".getBytes(UTF_8);

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
    public void updatesOnlyTheBinaryAfterVerification() throws Exception {
        assertThat(run(hash()), is(0));
        assertThat(Files.readAllBytes(manager), is(MANAGER));
        assertThat(Files.readString(wrapper), is("original script"));
        assertStagingCleaned();
    }

    @Test
    public void preservesBothFilesWhenTheChecksumDoesNotMatch() throws Exception {
        var failure = assertThrows(IOException.class, () -> run("0".repeat(64)));
        assertThat(failure.getMessage(), containsString("SHA-256 does not match"));
        assertOriginalFiles();
    }

    private int run(String digest) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/manager", request -> {
            try (request) {
                request.sendResponseHeaders(200, MANAGER.length);
                request.getResponseBody().write(MANAGER);
            }
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new HttpClient(operations.proxySettings());
            return new ManagerUpdate(operations, client).run(directory, url + "/manager", digest);
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
