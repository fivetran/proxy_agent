package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    private final @Nullable InstallerProxy proxy;
    private final NativeRequest nativeRequest;

    InstallerHttp(@Nullable InstallerProxy proxy, NativeRequest nativeRequest) {
        this.proxy = proxy;
        this.nativeRequest = nativeRequest;
        // A dropped response does not tell us whether Fivetran processed the POST.
        System.setProperty("sun.net.http.retryPost", "false");
    }

    /** Native transport for routing or authentication that the JDK cannot provide. */
    interface NativeRequest {
        Response request(URI uri, @Nullable String token) throws IOException, InterruptedException;
    }

    record Response(int status, byte[] body) {}

    private interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    void download(String url, Path target) throws IOException, InterruptedException {
        try {
            get(url, body -> {
                try (var file = Files.newOutputStream(target)) {
                    return body.transferTo(file);
                }
            });
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    List<String> registryTags(String url) throws IOException, InterruptedException {
        try {
            var tags = TAGS.matcher(get(url, body -> new String(body.readAllBytes(), UTF_8)));
            return tags.find()
                    ? QUOTED_TAG.matcher(tags.group(1)).results().map(match -> match.group(1)).toList()
                    : List.of();
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
    }

    private <T> T get(String url, BodyReader<T> read) throws IOException, InterruptedException {
        var uri = parseUrl(null, url);
        for (var redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (proxy == null || proxy.requiresNativeTransport(uri)) {
                return readNative(uri, read);
            }
            var connection = open(uri, proxy);
            try {
                connectHttps(connection);
                var status = connection.getResponseCode();
                if (status == 407 && connection.usingProxy() && connection.getHeaderField("Proxy-Authenticate") != null) {
                    throw new ProxyConnectionFailure(false);
                }
                if (status >= 200 && status < 300) {
                    try (var body = connection.getInputStream()) {
                        return read.read(body);
                    }
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
            } catch (ProxyConnectionFailure e) {
                if (!e.canUseNativeTransport(proxy)) {
                    throw e;
                }
                connection.disconnect();
                return readNative(uri, read);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private <T> T readNative(URI uri, BodyReader<T> read) throws IOException, InterruptedException {
        var response = nativeRequest.request(uri, null);
        if (response.status() < 200 || response.status() >= 300) {
            throw new IOException("Download returned HTTP " + response.status());
        }
        return read.read(new ByteArrayInputStream(response.body()));
    }

    byte[] configure(String url, String token) throws IOException, InterruptedException {
        checkToken(token);
        Response response;
        try {
            var uri = parseUrl(null, url);
            response = post(uri, token);
        } catch (IOException e) {
            throw new IOException("Failed to connect to configure endpoint", e);
        }
        if (response.status() == 401) {
            throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
        }
        if (response.status() != 200) {
            throw new IOException("Configure endpoint returned HTTP " + response.status());
        }
        return response.body();
    }

    private Response post(URI uri, String token) throws IOException, InterruptedException {
        // Plain HTTP has no CONNECT handshake. Use the native transport up front where it
        // may need to authenticate a proxy, since a forwarded 407 could come from the origin.
        if (proxy == null || proxy.requiresNativeTransport(uri)
                || (proxy.supportsNativeAuthentication() && uri.getScheme().equalsIgnoreCase("http")
                        && proxy.usesExplicitProxy(uri))) {
            return nativeRequest.request(uri, token);
        }
        try {
            return postJava(uri, token, proxy);
        } catch (ProxyConnectionFailure e) {
            if (!e.canUseNativeTransport(proxy)) {
                throw e;
            }
            return nativeRequest.request(uri, token);
        }
    }

    private static Response postJava(URI uri, String token, InstallerProxy proxy) throws IOException {
        var connection = open(uri, proxy);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Basic " + token);
            connection.setRequestProperty("Accept", "application/json");
            connectHttps(connection);
            var status = connection.getResponseCode();
            if (status == 200) {
                try (var body = connection.getInputStream()) {
                    return new Response(status, body.readAllBytes());
                }
            }
            return new Response(status, new byte[0]);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URI uri, InstallerProxy proxy) throws IOException {
        var connection = proxy.open(uri);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static void connectHttps(HttpURLConnection connection) throws IOException {
        // HTTPS connect establishes the tunnel and validates the certificate before sending any
        // origin authorization or POST. Only failures at this stage may fall back to Windows.
        if (connection.getURL().getProtocol().equals("https")) {
            try {
                connection.connect();
            } catch (SSLHandshakeException e) {
                if (e.getCause() instanceof CertificateException) {
                    throw new ProxyConnectionFailure(true);
                }
                throw e;
            } catch (IOException e) {
                if (e.getMessage() != null && PROXY_CONNECT_REJECTED.matcher(e.getMessage()).matches()) {
                    throw new ProxyConnectionFailure(false);
                }
                throw e;
            }
        }
    }

    /** A proxy or certificate failure eligible for native transport. */
    private static final class ProxyConnectionFailure extends IOException {
        private final boolean certificateFailure;

        private ProxyConnectionFailure(boolean certificateFailure) {
            super("Unable to authenticate the proxy or verify the HTTPS certificate");
            this.certificateFailure = certificateFailure;
        }

        private boolean canUseNativeTransport(InstallerProxy proxy) {
            return certificateFailure ? proxy.supportsNativeCertificateTrust() : proxy.supportsNativeAuthentication();
        }
    }

    private static void checkToken(String token) throws IOException {
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("Invalid TOKEN");
        }
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
