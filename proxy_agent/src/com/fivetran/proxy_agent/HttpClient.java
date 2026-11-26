package com.fivetran.proxy_agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Makes HTTP requests using the manager proxy settings. */
class HttpClient {
    private static final int TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private final ProxySettings proxy;

    HttpClient(ProxySettings proxy) {
        this.proxy = proxy;
        // A dropped response does not tell us whether the server processed the POST.
        System.setProperty("sun.net.http.retryPost", "false");
    }

    private interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    void download(String url, Path target) throws IOException {
        get(url, body -> {
            try (var file = Files.newOutputStream(target)) {
                return body.transferTo(file);
            }
        });
    }

    byte[] get(String url) throws IOException {
        return get(url, InputStream::readAllBytes);
    }

    private <T> T get(String url, BodyReader<T> read) throws IOException {
        var uri = parseUrl(null, url);
        for (var redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            var connection = open(uri);
            try {
                var status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    try (var body = connection.getInputStream()) {
                        return read.read(body);
                    }
                }
                var location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) {
                    throw new IOException("GET returned HTTP " + status);
                }
                var destination = parseUrl(uri, location);
                if (uri.getScheme().equalsIgnoreCase("https") && destination.getScheme().equalsIgnoreCase("http")) {
                    throw new IOException("GET redirected from HTTPS to HTTP");
                }
                uri = destination;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    Response post(String url, Map<String, String> headers) throws IOException {
        var connection = open(parseUrl(null, url));
        try {
            connection.setRequestMethod("POST");
            headers.forEach(connection::setRequestProperty);
            var status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                try (var body = connection.getInputStream()) {
                    return new Response(status, body.readAllBytes());
                }
            }
            return new Response(status, new byte[0]);
        } finally {
            connection.disconnect();
        }
    }

    record Response(int status, byte[] body) {}

    private HttpURLConnection open(URI uri) throws IOException {
        var connection = proxy.open(uri);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        return connection;
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
        throw new IOException("Invalid HTTP URL");
    }
}
