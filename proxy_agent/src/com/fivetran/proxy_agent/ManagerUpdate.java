package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stages a verified native manager and its matching compatibility script. */
final class ManagerUpdate {
    private static final String RELEASE = "https://github.com/fivetran/proxy_agent/releases/download/";
    private static final String LATEST_RELEASE = "https://api.github.com/repos/fivetran/proxy_agent/releases/latest";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern MANAGER_TAG = Pattern.compile("manager-v[0-9][0-9A-Za-z._-]*");

    private final OsOperations operations;
    private final HttpClient http;

    ManagerUpdate(OsOperations operations, HttpClient http) {
        this.operations = operations;
        this.http = http;
    }

    int run(Path directory, String binaryUrl, String digest, String scriptUrl) throws IOException, InterruptedException {
        if (binaryUrl.isEmpty() != digest.isEmpty()) {
            throw new IOException("Native manager download requires a URL and its SHA-256 digest.");
        }
        if (binaryUrl.isEmpty()) {
            binaryUrl = releaseBase() + operations.managerReleaseAsset();
            digest = new String(http.get(binaryUrl + ".sha256"), US_ASCII).strip();
        }
        if (!SHA256.matcher(digest).matches()) {
            throw new IOException("Invalid native manager SHA-256 digest.");
        }

        var staging = Files.createTempDirectory(directory, ".manager-update-");
        var deferred = false;
        try {
            var binary = staging.resolve(operations.managerBinaryName());
            var script = staging.resolve(operations.managerScriptName());
            System.out.println("Downloading native manager...");
            http.download(binaryUrl, binary);
            if (!digest.equalsIgnoreCase(digest(binary))) {
                throw new IOException("Native manager SHA-256 does not match.");
            }
            operations.makeExecutable(binary);
            System.out.println("Downloading management script...");
            http.download(scriptUrl.isEmpty() ? companionScript(binaryUrl) : scriptUrl, script);
            if (Files.size(script) == 0 || !operations.validManagerScript(script)) {
                throw new IOException("Downloaded management script is empty or has invalid syntax.");
            }
            operations.makeExecutable(script);
            deferred = operations.publishManagerUpdate(directory, staging);
            System.out.println((deferred ? "Native manager update scheduled for process exit." : "Native manager updated.")
                    + " SHA-256 " + digest.toLowerCase(Locale.ROOT) + ".");
            return 0;
        } finally {
            if (!deferred) {
                Files.deleteIfExists(staging.resolve(operations.managerBinaryName()));
                Files.deleteIfExists(staging.resolve(operations.managerScriptName()));
                Files.deleteIfExists(staging);
            }
        }
    }

    private String releaseBase() throws IOException {
        var response = http.get(LATEST_RELEASE);
        try (var parser = new JsonFactory().createParser(response)) {
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                for (var field = parser.nextFieldName(); field != null; field = parser.nextFieldName()) {
                    if (parser.nextToken() == JsonToken.VALUE_STRING && field.equals("tag_name")) {
                        var tag = parser.getText();
                        if (MANAGER_TAG.matcher(tag).matches()) {
                            return RELEASE + tag + "/";
                        }
                        break;
                    }
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            throw new IOException("Invalid latest manager release response.", e);
        }
        throw new IOException("The latest release does not have a manager release tag.");
    }

    private String companionScript(String binaryUrl) throws IOException {
        try {
            return URI.create(binaryUrl).resolve(operations.managerScriptName()).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid native manager download URL.");
        }
    }

    private static String digest(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file);
                    var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                input.transferTo(output);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
