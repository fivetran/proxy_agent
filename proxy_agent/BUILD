load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

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

java_binary(
    name = "test_manager",
    testonly = True,
    srcs = ["test/com/fivetran/proxy_agent/TestManager.java"],
    main_class = "com.fivetran.proxy_agent.TestManager",
    deps = [":proxy_agent"],
)

java_test(
    name = "update_manager_tests",
    size = "small",
    srcs = ["test/com/fivetran/proxy_agent/UpdateManagerTest.java"],
    tags = ["requires-network"],
    test_class = "com.fivetran.proxy_agent.UpdateManagerTest",
    deps = [
        ":proxy_agent",
        "@hamcrest//jar",
        "@junit4//jar",
    ],
)
