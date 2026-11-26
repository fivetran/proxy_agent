package com.fivetran.proxy_agent;

import static com.fivetran.proxy_agent.InstallHost.env;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Applies the manager environment settings to JDK proxy properties. */
final class ProxySettings {
    private static final String SOCKS_HOST = "socksProxyHost";
    private static final String SOCKS_PORT = "socksProxyPort";
    private static final String SOCKS_VERSION = "socksProxyVersion";
    private static final String SYSTEM_PROXIES = "java.net.useSystemProxies";
    private static final String INTEGRATED_SCHEMES = "NTLM,Negotiate,Kerberos";

    private final String http;
    private final String https;
    private final boolean environmentProxy;
    private final @Nullable Authenticator defaultAuthenticator = Authenticator.getDefault();

    private ProxySettings(String http, String https, String exclusions) {
        var explicitJdkProxy = Stream.of("http.proxyHost", "https.proxyHost", SOCKS_HOST)
                .anyMatch(property -> !System.getProperty(property, "").isEmpty());
        this.http = explicitJdkProxy ? "" : http;
        this.https = explicitJdkProxy ? "" : https;
        this.environmentProxy = !this.http.isEmpty() || !this.https.isEmpty();
        if (environmentProxy) {
            var bypass = Arrays.stream(exclusions.split(","))
                    .map(String::strip)
                    .filter(value -> !value.isEmpty() && !value.contains("/") && !value.contains("|"))
                    .map(value -> value.startsWith(".") ? value.substring(1) : value)
                    .filter(value -> !value.isEmpty())
                    .flatMap(value -> value.equals("*") ? Stream.of(value) : Stream.of(value, "*." + value))
                    .toList();
            var patterns = String.join("|", bypass);
            System.getProperties().putIfAbsent("http.nonProxyHosts", patterns);
            System.getProperties().putIfAbsent("socksNonProxyHosts", patterns);
        }
        if (this.http.contains("@") || this.https.contains("@")) {
            // Explicit credentials must take precedence over ambient credentials.
            disableIntegratedAuth("jdk.http.auth.tunneling.disabledSchemes");
            disableIntegratedAuth("jdk.http.auth.proxying.disabledSchemes");
        }
    }

    /** Initializes proxy settings once before executing a manager command. */
    static ProxySettings initialize(boolean windows) {
        return windows ? forWindows() : forUnix();
    }

    private static ProxySettings forUnix() {
        var all = env("all_proxy", env("ALL_PROXY", ""));
        return new ProxySettings(env("http_proxy", all), env("https_proxy", env("HTTPS_PROXY", all)),
                env("no_proxy", env("NO_PROXY", "")));
    }

    /** Uses Windows proxy discovery unless an explicit route is provided. */
    private static ProxySettings forWindows() {
        if (System.getProperty(SYSTEM_PROXIES) == null) {
            System.setProperty(SYSTEM_PROXIES, "true");
        }
        var proxy = env("HTTPS_PROXY", env("HTTP_PROXY", ""));
        return new ProxySettings(proxy, proxy, env("NO_PROXY", ""));
    }

    private static void disableIntegratedAuth(String property) {
        var disabled = System.getProperty(property, "");
        System.setProperty(property, disabled.isEmpty() ? INTEGRATED_SCHEMES : disabled + "," + INTEGRATED_SCHEMES);
    }

    /** Opens an HTTP connection using the default JDK proxy selector. */
    HttpURLConnection open(URI uri) throws IOException {
        var protocol = uri.getScheme().toLowerCase(Locale.ROOT);
        var address = protocol.equals("https") ? https : http;
        if (environmentProxy) {
            // Each manager request is completed before opening the next one. SOCKS properties are JVM-wide.
            System.clearProperty(SOCKS_HOST);
            Authenticator.setDefault(defaultAuthenticator);
        }
        if (address.isEmpty()) {
            return (HttpURLConnection) uri.toURL().openConnection();
        }
        var proxy = parse(address);
        if (proxy.scheme().equals("http")) {
            System.setProperty(protocol + ".proxyHost", proxy.host());
            System.setProperty(protocol + ".proxyPort", Integer.toString(proxy.port()));
            var connection = (HttpURLConnection) uri.toURL().openConnection();
            if (proxy.userInfo() != null) {
                connection.setAuthenticator(authenticator(proxy));
            }
            return connection;
        }

        System.setProperty(SOCKS_HOST, proxy.host());
        System.setProperty(SOCKS_PORT, Integer.toString(proxy.port()));
        System.setProperty(SOCKS_VERSION, proxy.scheme().equals("socks4") ? "4" : "5");
        if (proxy.userInfo() != null) {
            // The JDK SOCKS socket uses the default authenticator, not the HTTP connection's authenticator.
            Authenticator.setDefault(authenticator(proxy));
        }
        return (HttpURLConnection) uri.toURL().openConnection();
    }

    private static ProxyAddress parse(String address) throws ConfigurationException {
        try {
            var uri = URI.create(address.contains("://") ? address : "http://" + address);
            var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost();
            if (host == null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new ConfigurationException("Invalid proxy URL");
            }
            var port = uri.getPort();
            var credentials = uri.getRawUserInfo();
            return switch (scheme) {
                case "http" -> new ProxyAddress(scheme, host, port < 0 ? 80 : port, credentials);
                case "socks", "socks5" -> new ProxyAddress(scheme, host, port < 0 ? 1080 : port, credentials);
                case "socks4" -> {
                    if (credentials != null) {
                        throw new ConfigurationException("SOCKS4 proxy URLs use the JDK's local username");
                    }
                    yield new ProxyAddress(scheme, host, port < 0 ? 1080 : port, null);
                }
                case "https" -> throw new ConfigurationException("The JDK does not support HTTPS proxy URLs");
                case "socks4a", "socks5h" ->
                        throw new ConfigurationException("The JDK cannot force proxy-side DNS for SOCKS URLs");
                default -> throw new ConfigurationException("Unsupported proxy URL scheme");
            };
        } catch (IllegalArgumentException e) {
            // URI diagnostics can contain the proxy password.
            throw new ConfigurationException("Invalid proxy URL");
        }
    }

    private static Authenticator authenticator(ProxyAddress proxy) {
        var credentials = proxy.userInfo().split(":", 2);
        var user = decode(credentials[0]);
        var password = credentials.length > 1 ? decode(credentials[1]) : "";
        return new Authenticator() {
            @Override
            protected @Nullable PasswordAuthentication getPasswordAuthentication() {
                var proxyRequest = proxy.scheme().equals("http")
                        ? getRequestorType() == RequestorType.PROXY
                        : "SOCKS5".equals(getRequestingProtocol()) && getRequestingURL() == null;
                if (!proxyRequest || !proxy.host().equalsIgnoreCase(getRequestingHost())
                        || proxy.port() != getRequestingPort()) {
                    return null;
                }
                return new PasswordAuthentication(user, password.toCharArray());
            }
        };
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), UTF_8);
    }

    private record ProxyAddress(String scheme, String host, int port, @Nullable String userInfo) {}

    /** A sanitized proxy setting error that can be displayed by the manager. */
    static final class ConfigurationException extends IOException {
        private ConfigurationException(String message) {
            super(message);
        }
    }
}
