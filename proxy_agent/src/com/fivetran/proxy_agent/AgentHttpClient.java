package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** HTTP downloads, registry lookups, and configuration requests for the proxy agent manager. */
final class AgentHttpClient {
    private static final int TIMEOUT_MILLIS = 30_000;
    private static final int MAX_REDIRECTS = 10;
    private static final Pattern TAGS = Pattern.compile("\"tags\"\\s*:\\s*\\[([^]]*)]");
    private static final Pattern QUOTED_TAG = Pattern.compile("\"([0-9]+\\.[0-9]+\\.[0-9]+-[a-z0-9.-]+)\"");

    private final ProxySettings proxy;

    AgentHttpClient(ProxySettings proxy) {
        this.proxy = proxy;
        // A dropped response does not tell us whether Fivetran processed the POST.
        System.setProperty("sun.net.http.retryPost", "false");
    }

    private interface BodyReader<T> {
        T read(InputStream body) throws IOException;
    }

    void download(String url, Path target) throws IOException {
        try {
            get(url, body -> {
                try (var file = Files.newOutputStream(target)) {
                    return body.transferTo(file);
                }
            });
        } catch (ProxySettings.ConfigurationException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    List<String> registryTags(String url) throws IOException {
        try {
            var tags = TAGS.matcher(get(url, body -> new String(body.readAllBytes(), UTF_8)));
            return tags.find()
                    ? QUOTED_TAG.matcher(tags.group(1)).results().map(match -> match.group(1)).toList()
                    : List.of();
        } catch (ProxySettings.ConfigurationException e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
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
                    throw new IOException("Download returned HTTP " + status);
                }
                var destination = parseUrl(uri, location);
                if (uri.getScheme().equalsIgnoreCase("https") && destination.getScheme().equalsIgnoreCase("http")) {
                    throw new IOException("Download redirected from HTTPS to HTTP");
                }
                uri = destination;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    byte[] configure(String url, String token) throws IOException {
        checkToken(token);
        var connection = open(parseUrl(null, url));
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Basic " + token);
            connection.setRequestProperty("Accept", "application/json");
            int status;
            byte[] contents = new byte[0];
            try {
                status = connection.getResponseCode();
                if (status == 200) {
                    try (var body = connection.getInputStream()) {
                        contents = body.readAllBytes();
                    }
                }
            } catch (IOException e) {
                throw new IOException("Failed to connect to configure endpoint", e);
            }
            if (status == 401) {
                throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
            }
            if (status != 200) {
                throw new IOException("Configure endpoint returned HTTP " + status);
            }
            return contents;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(URI uri) throws IOException {
        var connection = proxy.open(uri);
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(false);
        return connection;
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
