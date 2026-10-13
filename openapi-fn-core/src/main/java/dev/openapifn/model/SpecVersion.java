package dev.openapifn.model;

import java.util.Optional;

/** Supported OpenAPI specification versions. */
public enum SpecVersion {
    V3_0("3.0"),
    V3_1("3.1"),
    V3_2("3.2");

    private final String prefix;

    SpecVersion(String prefix) {
        this.prefix = prefix;
    }

    /** Identifies the version from the {@code openapi} field, e.g. {@code 3.1.0}. */
    public static Optional<SpecVersion> of(String openapi) {
        for (var version : values()) {
            if (openapi.equals(version.prefix) || openapi.startsWith(version.prefix + ".")) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
