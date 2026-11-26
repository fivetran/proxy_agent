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

java_test(
    name = "tests",
    size = "small",
    srcs = ["test/com/fivetran/proxy_agent/ManagerCommandTest.java"],
    args = [
        "execute",
        "--fail-if-no-tests",
        "--include-engine=junit-jupiter",
        "--select-class=com.fivetran.proxy_agent.ManagerCommandTest",
    ],
    main_class = "org.junit.platform.console.ConsoleLauncher",
    use_testrunner = False,
    deps = [
        ":proxy_agent",
        "@hamcrest//jar",
        "@junit5//jar",
    ],
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
    args = [
        "execute",
        "--fail-if-no-tests",
        "--include-engine=junit-jupiter",
        "--select-class=com.fivetran.proxy_agent.UpdateManagerTest",
    ],
    main_class = "org.junit.platform.console.ConsoleLauncher",
    tags = ["requires-network"],
    use_testrunner = False,
    deps = [
        ":proxy_agent",
        "@hamcrest//jar",
        "@junit5//jar",
    ],
)
