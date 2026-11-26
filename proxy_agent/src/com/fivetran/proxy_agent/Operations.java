package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Host operations used by the installer. */
interface Operations {
    static Operations forCurrentHost() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? new WindowsOperations() : new UnixOperations();
    }

    boolean isWindows();

    void checkUser() throws IOException, InterruptedException;

    void checkAutoStart(List<String> warnings) throws IOException, InterruptedException;

    int cpuCount() throws IOException;

    long totalMemoryMb() throws IOException, InterruptedException;

    String windowsImageVariant() throws IOException, InterruptedException;

    String legacyCredential(Path path, String config) throws IOException, InterruptedException;

    default void validateFetchedConfig(byte[] config) throws IOException, InterruptedException {}

    Path createProtectedConfig(Path directory) throws IOException, InterruptedException;

    void makeExecutable(Path path) throws IOException;

    void start(Path manager) throws IOException, InterruptedException;
}
