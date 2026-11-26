package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Supplies the local compatibility launcher for an installed native manager. */
final class ManagerWrapper {
    private ManagerWrapper() {}

    static byte[] contents(String name) throws IOException {
        try (var resource = ManagerWrapper.class.getResourceAsStream("/com/fivetran/proxy_agent/compat/" + name)) {
            if (resource == null) {
                throw new IOException("Missing bundled manager wrapper.");
            }
            return resource.readAllBytes();
        }
    }

    /** Stages a forwarder only when an older script already occupies the installed path. */
    static void stageMigration(OsOperations operations, Path directory, Path staging) throws IOException {
        var name = operations.managerScriptName();
        var existing = directory.resolve(name);
        if (Files.isRegularFile(existing)) {
            var contents = contents(name);
            if (!Arrays.equals(contents, Files.readAllBytes(existing))) {
                var target = staging.resolve(name);
                Files.write(target, contents);
                operations.makeExecutable(target);
            }
        }
    }
}
