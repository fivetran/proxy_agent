package com.fivetran.proxy_agent;

/** Access to the environment variables used by manager commands. */
final class Environment {
    private Environment() {}

    static String get(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
