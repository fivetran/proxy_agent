package com.fivetran.proxy_agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Host operations used by the installer. */
interface OsOperations {
    static OsOperations forCurrentHost(CommandRunner commands) {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? new WindowsOperations(commands) : new UnixOperations(commands);
    }

    void configureProxy();

    Path defaultInstallDirectory();

    String managerScriptName();

    String managerBinaryName();

    long reservedMemoryMb();

    Settings settings(int memoryMb, String windowsVersion);

    void checkWindowsVersionOverride(String version);

    String containerOs(List<String> errors) throws IOException, InterruptedException;

    default void describeLinuxImage() {}

    default @Nullable Path diskCheckTarget(Path directory) {
        return directory.getParent();
    }

    default void warnMissingDiskTarget(@Nullable Path target, List<String> warnings) {}

    /** Checks whether the named executable can be found on this host. */
    boolean hasCommand(String name) throws IOException, InterruptedException;

    void checkUser() throws IOException, InterruptedException;

    void checkAutoStart(List<String> warnings) throws IOException, InterruptedException;

    int cpuCount() throws IOException;

    long totalMemoryMb() throws IOException, InterruptedException;

    String windowsImageVariant() throws IOException, InterruptedException;

    Path createProtectedConfig(Path directory) throws IOException, InterruptedException;

    void makeExecutable(Path path) throws IOException;

    int start(Path manager) throws IOException, InterruptedException;

    record Settings(String fileName, String contents) {}
}
