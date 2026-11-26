package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Installer downloads and API requests using the JDK's HTTP implementation. */
final class InstallerHttp {
    private static final int TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private static final String DISABLED_TUNNEL_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    private final String httpProxy = InstallHost.env("http_proxy", "");
    private final String httpsProxy = InstallHost.env("https_proxy", InstallHost.env("HTTPS_PROXY", ""));
    private final String allProxy = InstallHost.env("all_proxy", InstallHost.env("ALL_PROXY", ""));
    private final String exclusions = InstallHost.env("no_proxy", InstallHost.env("NO_PROXY", ""));

    InstallerHttp() {
        if ((httpProxy.contains("@") || httpsProxy.contains("@") || allProxy.contains("@"))
                && System.getProperty(DISABLED_TUNNEL_SCHEMES) == null) {
            // An explicitly credentialed HTTP proxy opts into Basic authentication for its CONNECT tunnel.
            // A per-connection authenticator sends the credentials only to that proxy.
            System.setProperty(DISABLED_TUNNEL_SCHEMES, "");
        }
    }

    void download(String url, Path target) throws IOException {
        try {
            var connection = openDownload(url);
            try (var body = connection.getInputStream(); var file = Files.newOutputStream(target, WRITE, TRUNCATE_EXISTING)) {
                body.transferTo(file);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    String registryTags(String url) throws IOException {
        try {
            var connection = openDownload(url);
            try (var body = connection.getInputStream()) {
                return new String(body.readAllBytes(), UTF_8);
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
    }

    byte[] configure(String url, String token) throws IOException {
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("Invalid TOKEN");
        }
        int status;
        String contents = "";
        try {
            var connection = open(parseUrl(url));
            try {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Basic " + token);
                connection.setRequestProperty("Accept", "application/json");
                status = connection.getResponseCode();
                if (status == 200) {
                    try (var body = connection.getInputStream()) {
                        contents = new String(body.readAllBytes(), UTF_8);
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            throw new IOException("Failed to connect to configure endpoint", e);
        }
        return InstallHost.configBody(Integer.toString(status), contents);
    }

    private HttpURLConnection openDownload(String url) throws IOException {
        var uri = parseUrl(url);
        for (var redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            var connection = open(uri);
            var success = false;
            try {
                var status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    success = true;
                    return connection;
                }
                var location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) {
                    throw new IOException("Download returned HTTP " + status);
                }
                var destination = parseUrl(uri, location);
                if (uri.getScheme().equalsIgnoreCase("https") && destination.getScheme().equalsIgnoreCase("http")) {
                    throw new IOException("Download redirected from HTTPS to HTTP");
                }
                uri = destination;
            } finally {
                if (!success) {
                    connection.disconnect();
                }
            }
        }
        throw new IOException("Too many redirects");
    }

    private HttpURLConnection open(URI uri) throws IOException {
        var proxy = configuredProxy(uri);
        var address = proxy == null ? Proxy.NO_PROXY
                : new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), port(proxy)));
        var connection = (HttpURLConnection) uri.toURL().openConnection(address);
        if (proxy != null && proxy.getRawUserInfo() != null) {
            connection.setAuthenticator(proxyAuthenticator(proxy));
        }
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static URI parseUrl(String url) throws IOException {
        return parseUrl(null, url);
    }

    private static URI parseUrl(@Nullable URI base, String url) throws IOException {
        try {
            var uri = base == null ? URI.create(url) : base.resolve(url);
            if (uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // Credential-bearing URLs must stay out of diagnostics.
        }
        throw new IOException("Invalid installer HTTP URL");
    }

    private @Nullable URI configuredProxy(URI uri) throws IOException {
        if (bypasses(uri.getHost())) {
            return null;
        }
        var address = uri.getScheme().equalsIgnoreCase("https") ? httpsProxy : httpProxy;
        if (address.isEmpty()) {
            address = allProxy;
        }
        if (address.isEmpty()) {
            return null;
        }
        var proxy = parseUrl(address.contains("://") ? address : "http://" + address);
        if (!"http".equalsIgnoreCase(proxy.getScheme())) {
            throw new IOException("Installer forward proxy must use HTTP");
        }
        return proxy;
    }

    private boolean bypasses(String hostname) {
        var host = hostname.toLowerCase(Locale.ROOT);
        for (var value : exclusions.split(",")) {
            var excluded = value.strip().toLowerCase(Locale.ROOT);
            if (excluded.startsWith(".")) {
                excluded = excluded.substring(1);
            }
            if (excluded.equals("*") || host.equals(excluded) || host.endsWith("." + excluded)) {
                return true;
            }
        }
        return false;
    }

    private static int port(URI proxy) {
        return proxy.getPort() < 0 ? 80 : proxy.getPort();
    }

    private static Authenticator proxyAuthenticator(URI proxy) {
        var credentials = proxy.getRawUserInfo().split(":", 2);
        var user = decodeUserInfo(credentials[0]);
        var password = credentials.length > 1 ? decodeUserInfo(credentials[1]) : "";
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

    private static String decodeUserInfo(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), UTF_8);
    }
}
