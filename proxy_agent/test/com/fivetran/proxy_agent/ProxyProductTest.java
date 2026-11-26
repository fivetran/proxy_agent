package com.fivetran.proxy_agent;

import static com.fivetran.proxy_agent.ProductTestSupport.MANAGER_SCRIPT;
import static com.fivetran.proxy_agent.ProductTestSupport.WINDOWS;
import static com.fivetran.proxy_agent.ProductTestSupport.digest;
import static com.fivetran.proxy_agent.ProductTestSupport.environment;
import static com.fivetran.proxy_agent.ProductTestSupport.run;
import static com.fivetran.proxy_agent.ProductTestSupport.send;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises proxy selection and credential handling with the packaged manager. */
public final class ProxyProductTest {
    private static final String PROXY_USER = "test-proxy-user";
    private static final String PROXY_PASSWORD = "test-proxy-secret";
    private static final String AUTHORIZATION = "Basic "
            + Base64.getEncoder().encodeToString((PROXY_USER + ":" + PROXY_PASSWORD).getBytes(UTF_8));
    private static final byte[] SCRIPT = "# replacement manager fixture\n".getBytes(UTF_8);
    private static final byte[] BINARY = "replacement native manager fixture".getBytes(UTF_8);

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void usesTheAuthenticatedProxyWithoutLoggingCredentials() throws Exception {
        check(Mode.PROXY);
    }

    @Test
    public void bypassesTheProxyForTheConfiguredHost() throws Exception {
        check(Mode.HOST_BYPASS);
    }

    @Test
    public void bypassesTheProxyWhenAllHostsAreExcluded() throws Exception {
        check(Mode.ALL_BYPASS);
    }

    private enum Mode { PROXY, HOST_BYPASS, ALL_BYPASS }

    private void check(Mode mode) throws Exception {
        var originRequests = new AtomicInteger();
        var proxyRequests = new AtomicInteger();
        var proxyAuthentication = new AtomicInteger();
        var host = originHost();
        try (var origin = new ProductTestSupport.Server(host, request -> {
            originRequests.incrementAndGet();
            send(request, 200, "application/octet-stream", asset(request.getRequestURI()));
        }); var proxy = new ProductTestSupport.Server("127.0.0.1", request -> {
            proxyRequests.incrementAndGet();
            if (!AUTHORIZATION.equals(request.getRequestHeaders().getFirst("Proxy-Authorization"))) {
                request.getResponseHeaders().set("Proxy-Authenticate", "Basic realm=\"proxy-agent-test\"");
                send(request, 407, "text/plain", new byte[0]);
                return;
            }
            proxyAuthentication.incrementAndGet();
            send(request, 200, "application/octet-stream", asset(request.getRequestURI()));
        })) {
            var nativeManager = System.getenv("PROXY_AGENT_MANAGER_BIN");
            if (nativeManager == null) {
                throw new AssertionError("Set PROXY_AGENT_MANAGER_BIN to the packaged native manager.");
            }
            var env = environment();
            env.put("PROXY_AGENT_SCRIPT_URL", origin.url() + "/" + MANAGER_SCRIPT);
            env.put("PROXY_AGENT_MANAGER_BINARY_URL", origin.url() + "/manager");
            env.put("PROXY_AGENT_MANAGER_BINARY_SHA256", digest(BINARY));
            env.put(WINDOWS ? "HTTP_PROXY" : "http_proxy", proxy.url().replace("http://",
                    "http://" + PROXY_USER + ":" + PROXY_PASSWORD + "@"));
            if (mode != Mode.PROXY) {
                env.put("NO_PROXY", mode == Mode.ALL_BYPASS ? "*" : host);
            }
            var installation = temporary.getRoot().toPath();
            var result = run(List.of(nativeManager, "--agent-dir", installation.toString(), "update-manager"), env, 30);
            result.expect(0);
            assertThat(result.output(), not(containsString(PROXY_USER)));
            assertThat(result.output(), not(containsString(PROXY_PASSWORD)));
            assertThat(Files.readAllBytes(installation.resolve(WINDOWS ? "manager.exe" : "manager")), is(BINARY));
            assertThat(Files.readAllBytes(installation.resolve(MANAGER_SCRIPT)), is(SCRIPT));
            assertThat(originRequests.get(), is(mode == Mode.PROXY ? 0 : 2));
            assertThat(proxyAuthentication.get(), is(mode == Mode.PROXY ? 2 : 0));
            if (mode != Mode.PROXY) {
                assertThat(proxyRequests.get(), is(0));
            }
        }
    }

    private static byte[] asset(URI request) {
        return request.getPath().equals("/manager") ? BINARY : SCRIPT;
    }

    private static String originHost() throws Exception {
        // A UDP connect selects an interface without sending a packet.
        try (var socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("192.0.2.1", 9));
            return socket.getLocalAddress().getHostAddress();
        }
    }
}
