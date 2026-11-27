package com.fivetran.proxy_agent;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Proxy agent operations shared by manager commands. */
final class Manager {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private static final String SCRIPT_ROOT = "https://raw.githubusercontent.com/fivetran/proxy_agent/main/";
    private static final String REGISTRY_URL =
            "https://us-docker.pkg.dev/v2/prod-eng-fivetran-public-repos/public-docker-us/proxy-agent/tags/list";

    private final OsOperations operations;
    private final CommandRunner commands;
    private final HttpClient http;

    Manager(OsOperations operations, CommandRunner commands, HttpClient http) {
        this.operations = operations;
        this.commands = commands;
        this.http = http;
    }

    OsOperations operations() {
        return operations;
    }

    CommandRunner commands() {
        return commands;
    }

    /** Downloads the native release and the corresponding platform script. */
    int updateManager(Path directory) throws IOException, InterruptedException {
        return new ManagerUpdate(operations, http).run(directory,
                SystemEnvironment.get("PROXY_AGENT_MANAGER_BINARY_URL", ""),
                SystemEnvironment.get("PROXY_AGENT_MANAGER_BINARY_SHA256", ""),
                SystemEnvironment.get("PROXY_AGENT_SCRIPT_URL", ""));
    }

    void downloadManagerScript(Path target) throws IOException {
        var url = SystemEnvironment.get("PROXY_AGENT_SCRIPT_URL", SCRIPT_ROOT + operations.managerScriptName());
        try {
            http.download(url, target);
        } catch (IOException e) {
            throw new IOException("Failed to download management script", e);
        }
    }

    List<String> registryTags() throws IOException {
        try {
            var bytes = http.get(SystemEnvironment.get("PROXY_AGENT_REGISTRY_TAGS_URL", REGISTRY_URL));
            var tags = new ArrayList<String>();
            try (var parser = JSON.createParser(bytes)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw new IOException("Expected a registry response object");
                }
                for (var field = parser.nextFieldName(); field != null; field = parser.nextFieldName()) {
                    if (parser.nextToken() == JsonToken.START_ARRAY && field.equals("tags")) {
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                                throw new IOException("Expected a registry tag");
                            }
                            tags.add(parser.getText());
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                if (!parser.hasToken(JsonToken.END_OBJECT) || parser.nextToken() != null) {
                    throw new IOException("Invalid registry response");
                }
            }
            return tags;
        } catch (IOException e) {
            throw new IOException("Unable to query image registry for latest version", e);
        }
    }

    byte[] fetchConfiguration(String token) throws IOException {
        if (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IOException("Invalid TOKEN");
        }
        var api = SystemEnvironment.get("FIVETRAN_API_URL", "https://api.fivetran.com").replaceAll("/+$", "");
        HttpClient.Response response;
        try {
            response = http.post(api + "/proxy-agent/configure",
                    Map.of("Authorization", "Basic " + token, "Accept", "application/json"));
        } catch (IOException e) {
            throw new IOException("Failed to connect to configure endpoint", e);
        }
        if (response.status() == 401) {
            throw new IOException("Authentication failed (HTTP 401). Ensure your TOKEN is valid.");
        }
        if (response.status() != 200) {
            throw new IOException("Configure endpoint returned HTTP " + response.status());
        }
        ConfigFields fields;
        try {
            fields = readConfigFields(response.body());
        } catch (IOException e) {
            throw new IOException("Configure endpoint returned an invalid JSON response", e);
        }
        if (fields.agentId() == null || fields.agentId().isBlank()) {
            throw new IOException("Configure endpoint response is missing a nonempty string agent_id");
        }
        return response.body();
    }

    /** Reads a saved WebSocket agent's credentials if the config needs to be migrated. */
    static @Nullable String legacyCredential(byte[] contents) throws IOException {
        ConfigFields fields;
        try {
            fields = readConfigFields(contents);
        } catch (IOException e) {
            throw new IOException("Config file does not contain a valid JSON object", e);
        }
        if (!fields.legacy()) {
            return null;
        }
        if (fields.agentId() == null || fields.agentId().isEmpty()
                || fields.authToken() == null || fields.authToken().isEmpty()) {
            throw new IOException("Could not extract credentials from WebSocket config");
        }
        return fields.agentId() + ":" + fields.authToken();
    }

    private static ConfigFields readConfigFields(byte[] bytes) throws IOException {
        String agentId = null;
        String authToken = null;
        var legacy = false;
        try (var parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected a config object");
            }
            for (var field = parser.nextFieldName(); field != null; field = parser.nextFieldName()) {
                var type = parser.nextToken();
                switch (field) {
                    case "agent_id" -> agentId = type == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "auth_token" -> authToken = type == JsonToken.VALUE_STRING ? parser.getText() : null;
                    case "proxy_server_uri" -> legacy = true;
                    default -> {}
                }
                parser.skipChildren();
            }
            if (!parser.hasToken(JsonToken.END_OBJECT) || parser.nextToken() != null) {
                throw new IOException("Invalid config object");
            }
        }
        return new ConfigFields(agentId, authToken, legacy);
    }

    void startAgent(Path script) throws IOException, InterruptedException {
        if (operations.start(script) != 0) {
            throw new IOException("Installation complete, but agent failed to start.");
        }
    }

    private record ConfigFields(@Nullable String agentId, @Nullable String authToken, boolean legacy) {}
}
