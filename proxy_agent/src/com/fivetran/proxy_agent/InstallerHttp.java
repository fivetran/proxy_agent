package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.regex.Pattern;
import javax.net.ssl.SSLHandshakeException;
import org.jspecify.annotations.Nullable;

/** Installer downloads and API requests using the JDK's HTTP implementation. */
final class InstallerHttp {
    private static final int TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private static final Pattern PROXY_CONNECT_REJECTED =
            Pattern.compile("Unable to tunnel through proxy\\. Proxy returns \"HTTP/[^ ]+ 407(?: .*)?\"");
    private static final Pattern TAGS = Pattern.compile("\"tags\"\\s*:\\s*\\[([^]]*)]");
    private static final Pattern QUOTED_TAG = Pattern.compile("\"([0-9]+\\.[0-9]+\\.[0-9]+-[a-z0-9.-]+)\"");

    private final InstallerProxy proxy;

    InstallerHttp(InstallerProxy proxy) {
        this.proxy = proxy;
    }

    void download(String url, Path target) throws IOException {
        try {
            var connection = openDownload(url);
            try (var body = connection.getInputStream(); var file = Files.newOutputStream(target, WRITE, TRUNCATE_EXISTING)) {
                body.transferTo(file);
            } finally {
                connection.disconnect();
            }
        } catch (WindowsTransportRequired e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    List<String> registryTags(String url) throws IOException {
        try {
            var connection = openDownload(url);
            try (var body = connection.getInputStream()) {
                var tags = TAGS.matcher(new String(body.readAllBytes(), UTF_8));
                if (tags.find()) {
                    return QUOTED_TAG.matcher(tags.group(1)).results().map(match -> match.group(1)).toList();
                }
                return List.of();
            } finally {
                connection.disconnect();
            }
        } catch (WindowsTransportRequired e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
    }

    byte[] configure(String url, String token) throws IOException {
        checkToken(token);
        int status;
        String contents = "";
        try {
            var connection = open(parseUrl(url));
            try {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Basic " + token);
                connection.setRequestProperty("Accept", "application/json");
                status = responseCode(connection);
                if (status == 200) {
                    try (var body = connection.getInputStream()) {
                        contents = new String(body.readAllBytes(), UTF_8);
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (WindowsTransportRequired e) {
            throw e;
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
                var status = responseCode(connection);
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
        var connection = proxy.open(uri);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static int responseCode(HttpURLConnection connection) throws IOException {
        // HTTPS connect establishes the tunnel and validates the certificate before sending any
        // origin authorization or POST. Only failures at this stage may fall back to Windows.
        if (connection.getURL().getProtocol().equals("https")) {
            try {
                connection.connect();
            } catch (SSLHandshakeException e) {
                if (e.getCause() instanceof CertificateException) {
                    throw new WindowsTransportRequired();
                }
                throw e;
            } catch (IOException e) {
                if (e.getMessage() != null && PROXY_CONNECT_REJECTED.matcher(e.getMessage()).matches()) {
                    throw new WindowsTransportRequired();
                }
                throw e;
            }
        }
        var status = connection.getResponseCode();
        if (status == 407 && connection.getURL().getProtocol().equals("http") && connection.usingProxy()
                && connection.getHeaderField("Proxy-Authenticate") != null) {
            throw new WindowsTransportRequired();
        }
        return status;
    }

    /** A Windows backend can handle this proxy or certificate failure without replaying an origin POST. */
    static final class WindowsTransportRequired extends IOException {
        private WindowsTransportRequired() {
            super("Unable to authenticate the proxy or verify the HTTPS certificate");
        }
    }

    static void checkToken(String token) throws IOException {
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("Invalid TOKEN");
        }
    }

    static URI parseUrl(String url) throws IOException {
        return parseUrl(null, url);
    }

    private static URI parseUrl(@Nullable URI base, String url) throws IOException {
        try {
            var uri = base == null ? URI.create(url) : base.resolve(url);
            if (uri.getHost() != null && uri.getRawUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // Credential-bearing URLs must stay out of diagnostics.
        }
        throw new IOException("Invalid installer HTTP URL");
    }
}
