package com.fivetran.proxy_agent;

import java.math.BigInteger;

/** Compares numeric major, minor, and patch versions. */
final class ReleaseVersion {
    private ReleaseVersion() {}

    static int compare(String left, String right) {
        var leftParts = left.split("\\.");
        var rightParts = right.split("\\.");
        for (var i = 0; i < leftParts.length; i++) {
            var comparison = new BigInteger(leftParts[i]).compareTo(new BigInteger(rightParts[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
