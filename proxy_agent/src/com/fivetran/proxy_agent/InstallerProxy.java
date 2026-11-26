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

    private final String http;
    private final String https;
    private final List<String> exclusions;
    private final boolean windows;
    private final boolean customJdkSettings;

    private InstallerProxy(String http, String https, String exclusions, boolean windows) {
        this.http = http;
        this.https = https;
        this.windows = windows;
        this.customJdkSettings = hasJdkProxySettings() || System.getProperty("javax.net.ssl.trustStore") != null;
        this.exclusions = Arrays.stream(exclusions.split(","))
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .filter(value -> !value.isEmpty())
                .toList();
        if (!http.isEmpty() || !https.isEmpty()) {
            // The JDK adds implicit loopback exclusions to nonempty http.nonProxyHosts.
            // Apply the installer's exact bypass list per request instead.
            System.setProperty("http.nonProxyHosts", "");
            // Explicit credentials must not fall through to ambient Windows or Kerberos credentials.
            disableIntegratedAuth(DISABLED_TUNNEL_SCHEMES);
            disableIntegratedAuth(DISABLED_PROXY_SCHEMES);
        }
    }

    /** Sets separate HTTP and HTTPS routes, following curl's variable precedence. */
    static InstallerProxy forUnix() {
        var all = env("all_proxy", env("ALL_PROXY", ""));
        return new InstallerProxy(env("http_proxy", all),
                env("https_proxy", env("HTTPS_PROXY", all)), env("no_proxy", env("NO_PROXY", "")), false);
    }

    /** Windows applies one explicit proxy to both protocols. */
    static InstallerProxy forWindows(String address) {
        return new InstallerProxy(address, address, env("NO_PROXY", ""), true);
    }

    /** Leaves explicitly supplied JDK settings in control of proxy selection and bypasses. */
    static InstallerProxy forJdk() {
        return new InstallerProxy("", "", "", false);
    }

    static boolean hasJdkProxySettings() {
        return Boolean.getBoolean("java.net.useSystemProxies")
                || !System.getProperty("http.proxyHost", "").isEmpty()
                || !System.getProperty("https.proxyHost", "").isEmpty()
                || !System.getProperty("socksProxyHost", "").isEmpty();
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
        var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        var address = proxyAddress(uri);
        if (address.isEmpty()) {
            return (HttpURLConnection) uri.toURL().openConnection();
        }
        if (bypasses(uri)) {
            return (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
        }

        var proxy = parseProxyUrl(address);
        System.setProperty(scheme + ".proxyHost", proxy.getHost());
        System.setProperty(scheme + ".proxyPort", Integer.toString(port(proxy)));
        var connection = (HttpURLConnection) uri.toURL().openConnection();
        if (proxy.getRawUserInfo() != null) {
            connection.setAuthenticator(authenticator(proxy));
        }
        return connection;
    }

    boolean usesExplicitProxy(URI uri) {
        return !proxyAddress(uri).isEmpty() && !bypasses(uri);
    }

    /** The JDK HTTP handler supports a plain HTTP forward proxy and host-based bypass rules. */
    boolean requiresNativeTransport(URI uri) {
        if (!usesExplicitProxy(uri)) {
            return false;
        }
        var address = proxyAddress(uri);
        var protocol = address.indexOf("://");
        if (protocol >= 0 && !address.substring(0, protocol).equalsIgnoreCase("http")) {
            return true;
        }
        var host = uri.getHost();
        return !windows && (host.indexOf(':') >= 0 || host.matches("[0-9.]+"))
                && exclusions.stream().anyMatch(rule -> rule.contains("/"));
    }

    boolean supportsNativeAuthentication() {
        return windows;
    }

    boolean supportsNativeCertificateTrust() {
        return !customJdkSettings;
    }

    private String proxyAddress(URI uri) {
        return uri.getScheme().equalsIgnoreCase("https") ? https : http;
    }

    private boolean bypasses(URI uri) {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        return exclusions.stream().anyMatch(excluded ->
                excluded.equals("*") || host.equals(excluded) || host.endsWith("." + excluded));
    }

    private static URI parseProxyUrl(String address) throws IOException {
        try {
            var proxy = URI.create(address.contains("://") ? address : "http://" + address);
            if (proxy.getHost() != null && proxy.getPort() != 0 && proxy.getPort() <= 65535
                    && "http".equalsIgnoreCase(proxy.getScheme())) {
                return proxy;
            }
        } catch (IllegalArgumentException e) {
            // URI diagnostics can contain the proxy password.
        }
        throw new IOException("Invalid HTTP proxy URL");
    }

    private static int port(URI proxy) {
        return proxy.getPort() < 0 ? 80 : proxy.getPort();
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
