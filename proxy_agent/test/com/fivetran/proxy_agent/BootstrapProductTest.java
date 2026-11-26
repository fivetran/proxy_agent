package com.fivetran.proxy_agent;

import static com.fivetran.proxy_agent.ProductTestSupport.EXTENSION;
import static com.fivetran.proxy_agent.ProductTestSupport.WINDOWS;
import static com.fivetran.proxy_agent.ProductTestSupport.digest;
import static com.fivetran.proxy_agent.ProductTestSupport.environment;
import static com.fivetran.proxy_agent.ProductTestSupport.repository;
import static com.fivetran.proxy_agent.ProductTestSupport.run;
import static com.fivetran.proxy_agent.ProductTestSupport.script;
import static com.fivetran.proxy_agent.ProductTestSupport.send;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the public bootstrappers with a local downloadable executable. */
public final class BootstrapProductTest {
    @ClassRule
    public static final TemporaryFolder fixtures = new TemporaryFolder();

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static Path launcher;
    private static byte[] payload;

    @BeforeClass
    public static void buildLauncher() throws Exception {
        var root = fixtures.getRoot().toPath();
        launcher = root.resolve(WINDOWS ? "fixture-manager.exe" : "fixture-manager");
        if (WINDOWS) {
            var source = root.resolve("Fixture.cs");
            Files.writeString(source, """
                    using System;
                    using System.IO;
                    public class Fixture {
                        public static int Main(string[] args) {
                            var output = Environment.GetEnvironmentVariable("BOOTSTRAP_TEST_RESULT");
                            File.WriteAllLines(output, args);
                            File.AppendAllText(output, Environment.GetEnvironmentVariable("PROXY_AGENT_INSTALLER_BINARY"));
                            return 7;
                        }
                    }
                    """);
            var compiler = Path.of(System.getenv("SystemRoot"), "Microsoft.NET/Framework64/v4.0.30319/csc.exe");
            run(List.of(compiler.toString(), "/nologo", "/out:" + launcher, source.toString()), environment(), 30).expect(0);
        } else {
            Files.writeString(launcher, """
                    #!/bin/sh
                    printf '%s\\n' "$@" "$PROXY_AGENT_INSTALLER_BINARY" > "$BOOTSTRAP_TEST_RESULT"
                    exit 7
                    """);
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        }
        payload = Files.readAllBytes(launcher);
    }

    @Test
    public void verifiesAndRunsTheDownloadedManager() throws Exception {
        verify(Mode.VERIFIED);
    }

    @Test
    public void rejectsAMismatchedDigestWithoutExecutingTheDownload() throws Exception {
        verify(Mode.WRONG_DIGEST);
    }

    @Test
    public void cleansUpAfterADownloadFailure() throws Exception {
        verify(Mode.FAILED_DOWNLOAD);
    }

    @Test
    public void resolvesAnOverrideFromPath() throws Exception {
        verify(Mode.PATH_OVERRIDE);
    }

    private enum Mode { VERIFIED, WRONG_DIGEST, FAILED_DOWNLOAD, PATH_OVERRIDE }

    private void verify(Mode mode) throws Exception {
        var root = temporary.getRoot().toPath();
        var resultFile = root.resolve("invocation");
        var scratch = Files.createDirectory(root.resolve("scratch"));
        var downloads = new AtomicInteger();
        try (var server = new ProductTestSupport.Server("127.0.0.1", request -> {
            downloads.incrementAndGet();
            send(request, mode == Mode.FAILED_DOWNLOAD ? 503 : 200, "application/octet-stream", payload);
        })) {
            var environment = environment();
            for (var variable : List.of("TMPDIR", "TMP", "TEMP")) {
                environment.put(variable, scratch.toString());
            }
            environment.put("BOOTSTRAP_TEST_RESULT", resultFile.toString());
            if (mode == Mode.PATH_OVERRIDE) {
                environment.put("PROXY_AGENT_MANAGER_BIN", launcher.getFileName().toString());
                var key = environment.keySet().stream().filter(name -> name.equalsIgnoreCase("PATH"))
                        .findFirst().orElseThrow();
                environment.put(key, launcher.getParent() + File.pathSeparator + environment.get(key));
            } else {
                environment.put("PROXY_AGENT_MANAGER_BINARY_URL", server.url() + "/manager");
                environment.put("PROXY_AGENT_MANAGER_BINARY_SHA256", mode == Mode.WRONG_DIGEST ? "0".repeat(64) : digest(payload));
            }
            var config = root.resolve("config with spaces.json").toString();
            var target = root.resolve("install with spaces").toString();
            var result = run(script(repository().resolve("install." + EXTENSION),
                    config, WINDOWS ? "-InstallDir" : "--install-dir", target), environment, 30);

            try (var contents = Files.list(scratch)) {
                assertThat(contents.count(), is(0L));
            }
            assertThat(downloads.get(), is(mode == Mode.PATH_OVERRIDE ? 0 : 1));
            if (mode == Mode.WRONG_DIGEST || mode == Mode.FAILED_DOWNLOAD) {
                result.expect(1);
                assertThat(result.output(), containsString(mode == Mode.WRONG_DIGEST ? "SHA-256 does not match" : "Failed to download"));
                assertThat(Files.exists(resultFile), is(false));
                return;
            }
            result.expect(7);
            var invocation = Files.readAllLines(resultFile);
            assertThat(invocation.subList(0, invocation.size() - 1), is(List.of("install", config, "--install-dir", target)));
            var executable = Path.of(invocation.get(invocation.size() - 1));
            assertThat(executable.isAbsolute(), is(true));
            if (mode == Mode.PATH_OVERRIDE) {
                assertThat(executable.toRealPath(), is(launcher.toRealPath()));
            } else {
                assertThat(executable.getParent().getParent().toRealPath(), is(scratch.toRealPath()));
            }
        }
    }
}
