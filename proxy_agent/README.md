# Native Proxy Agent manager

CI uses Bazel and GraalVM Community Edition to build the Java CLI for Linux
x86-64, Linux AArch64, and Windows x86-64. Each build runs the native
executable with `--help` and stores the binary and its checksum as workflow
artifacts.

Build the Java deploy JAR and compile it with Native Image for your host.
Windows builds require MSVC and the Windows SDK.

```bash
bazel build //proxy_agent:manager_deploy.jar
mkdir -p dist
native-image -jar bazel-bin/proxy_agent/manager_deploy.jar dist/manager
```
