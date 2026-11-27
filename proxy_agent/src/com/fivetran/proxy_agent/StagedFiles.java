package com.fivetran.proxy_agent;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/** Keeps replacement files beside their destinations until they are ready to install. */
final class StagedFiles implements AutoCloseable {
    private final ArrayList<Replacement> replacements = new ArrayList<>();

    Path create(Path target) throws IOException {
        var temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        add(temporary, target);
        return temporary;
    }

    void add(Path temporary, Path target) {
        replacements.add(new Replacement(temporary, target));
    }

    void install() throws IOException {
        for (var replacement : replacements) {
            Files.move(replacement.temporary(), replacement.target(), ATOMIC_MOVE, REPLACE_EXISTING);
        }
    }

    @Override
    public void close() throws IOException {
        @Nullable IOException failure = null;
        for (var replacement : replacements) {
            try {
                Files.deleteIfExists(replacement.temporary());
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record Replacement(Path temporary, Path target) {}
}
