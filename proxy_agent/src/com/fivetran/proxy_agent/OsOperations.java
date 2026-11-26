package com.fivetran.proxy_agent;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Host operations used by the installer. */
interface OsOperations {
    static OsOperations forCurrentHost(CommandRunner commands, Docker docker) {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") ? new WindowsOperations(commands, docker) : new UnixOperations(commands, docker);
    }

    void configureProxy();

    Path defaultInstallDirectory();

    String managerScriptName();

    String managerBinaryName();

    /** Returns the release artifact for the running host and architecture. */
    String managerReleaseAsset() throws IOException;

    /** Checks the platform script's syntax without running it. */
    boolean validManagerScript(Path script) throws IOException, InterruptedException;

    /** Publishes the staged update and reports whether a helper owns the staging directory. */
    default boolean publishManagerUpdate(Path directory, Path staging) throws IOException {
        for (var file : List.of(managerBinaryName(), managerScriptName())) {
            Files.move(staging.resolve(file), directory.resolve(file), ATOMIC_MOVE, REPLACE_EXISTING);
        }
        return false;
    }

    long reservedMemoryMb();

    Settings settings(int memoryMb, String windowsVersion);

    /** Evaluates settings written for the existing platform manager. */
    ContainerSettings containerSettings(Path directory) throws IOException, InterruptedException;

    default void saveWindowsVariant(Path directory, String variant) throws IOException {}

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

    String windowsImageVariant() throws IOException, InterruptedException;

    Path createProtectedConfig(Path directory) throws IOException, InterruptedException;

    void makeExecutable(Path path) throws IOException;

    int start(Path manager) throws IOException, InterruptedException;

    record Settings(String fileName, String contents) {}

    record ContainerSettings(String memoryMb, String windowsVersion) {}
}
