package com.fivetran.proxy_agent;

import static com.fivetran.proxy_agent.InstallHost.env;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Adapts installer proxy environment variables to the JDK's HTTP proxy selector. */
final class InstallerProxy {
    private static final String DISABLED_TUNNEL_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";
    private static final String DISABLED_PROXY_SCHEMES = "jdk.http.auth.proxying.disabledSchemes";
    private static final String INTEGRATED_SCHEMES = "NTLM,Negotiate,Kerberos";

    private final @Nullable URI http;
    private final @Nullable URI https;
    private final List<String> exclusions;

    private InstallerProxy(@Nullable URI http, @Nullable URI https, String exclusions) {
        this.http = http;
        this.https = https;
        this.exclusions = Arrays.stream(exclusions.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .filter(value -> !value.isEmpty())
                .toList();
        setProxy("http", http);
        setProxy("https", https);
        if (http != null || https != null) {
            // The JDK adds implicit loopback exclusions to nonempty http.nonProxyHosts.
            // Apply the installer's exact bypass list per request instead.
            System.setProperty("http.nonProxyHosts", "");
            // Explicit credentials must not fall through to ambient Windows or Kerberos credentials.
            disableIntegratedAuth(DISABLED_TUNNEL_SCHEMES);
            disableIntegratedAuth(DISABLED_PROXY_SCHEMES);
        }
    }

    /** Sets separate HTTP and HTTPS routes, following curl's variable precedence. */
    static InstallerProxy forUnix() throws IOException {
        var all = env("all_proxy", env("ALL_PROXY", ""));
        return new InstallerProxy(parse(env("http_proxy", all)),
                parse(env("https_proxy", env("HTTPS_PROXY", all))), env("no_proxy", env("NO_PROXY", "")));
    }

    /** Windows uses the same explicit proxy for both protocols and otherwise uses Internet Options. */
    static InstallerProxy forWindows(@Nullable URI proxy) {
        if (proxy == null) {
            System.setProperty("java.net.useSystemProxies", "true");
        }
        return new InstallerProxy(proxy, proxy, env("NO_PROXY", ""));
    }

    private static void disableIntegratedAuth(String property) {
        var disabled = System.getProperty(property, "");
        System.setProperty(property, disabled.isEmpty() ? INTEGRATED_SCHEMES : disabled + "," + INTEGRATED_SCHEMES);
    }

    /**
     * Opens the JDK connection with credentials scoped to the configured proxy.
     * A bypass is passed explicitly so the JDK never supplies proxy credentials on a direct request.
     */
    HttpURLConnection open(URI uri) throws IOException {
        var proxy = uri.getScheme().equalsIgnoreCase("https") ? https : http;
        if (proxy != null && bypasses(uri)) {
            return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        }
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        if (proxy != null && hasCredentials(proxy)) {
            connection.setAuthenticator(authenticator(proxy));
        }
        return connection;
    }

    private boolean bypasses(URI uri) {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        return exclusions.stream().anyMatch(excluded ->
                excluded.equals("*") || host.equals(excluded) || host.endsWith("." + excluded));
    }

    private static @Nullable URI parse(String address) throws IOException {
        if (address.isEmpty()) {
            return null;
        }
        var proxy = parseProxyUrl(address);
        if (!"http".equalsIgnoreCase(proxy.getScheme())) {
            throw new IOException("Installer forward proxy must use HTTP");
        }
        return proxy;
    }

    static URI parseProxyUrl(String address) throws IOException {
        try {
            var proxy = URI.create(address.contains("://") ? address : "http://" + address);
            if (proxy.getHost() != null && proxy.getPort() != 0 && proxy.getPort() <= 65535
                    && ("http".equalsIgnoreCase(proxy.getScheme()) || "https".equalsIgnoreCase(proxy.getScheme()))) {
                return proxy;
            }
        } catch (IllegalArgumentException e) {
            // URI diagnostics can contain the proxy password.
        }
        throw new IOException("Invalid HTTP proxy URL");
    }

    private static void setProxy(String protocol, @Nullable URI proxy) {
        if (proxy != null) {
            System.setProperty(protocol + ".proxyHost", proxy.getHost());
            System.setProperty(protocol + ".proxyPort", Integer.toString(port(proxy)));
        }
    }

    private static boolean hasCredentials(@Nullable URI proxy) {
        return proxy != null && proxy.getRawUserInfo() != null;
    }

    private static int port(URI proxy) {
        return proxy.getPort() < 0 ? ("https".equalsIgnoreCase(proxy.getScheme()) ? 443 : 80) : proxy.getPort();
    }

    private static Authenticator authenticator(URI proxy) {
        var credentials = proxy.getRawUserInfo().split(":", 2);
        var user = decode(credentials[0]);
        var password = credentials.length > 1 ? decode(credentials[1]) : "";
        return new Authenticator() {
            @Override
            protected @Nullable PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY || !proxy.getHost().equalsIgnoreCase(getRequestingHost())
                        || port(proxy) != getRequestingPort()) {
                    return null;
                }
                return new PasswordAuthentication(user, password.toCharArray());
            }
        };
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), UTF_8);
    }
}
