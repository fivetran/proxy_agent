load("@rules_java//java:defs.bzl", "java_binary", "java_library")

java_library(
    name = "proxy_agent",
    srcs = glob(["src/**/*.java"]),
    resource_strip_prefix = "proxy_agent/resources",
    resources = glob(["resources/**"]),
    deps = [
        "@jackson_core//jar",
        "@jspecify//jar",
        "@picocli//jar",
    ],
)

java_binary(
    name = "manager",
    main_class = "com.fivetran.proxy_agent.Application",
    runtime_deps = [":proxy_agent"],
)
