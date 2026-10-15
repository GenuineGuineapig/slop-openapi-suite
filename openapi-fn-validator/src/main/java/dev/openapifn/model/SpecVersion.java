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

    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile("(3\\.[0-2])\\.[0-9]+(-.+)?");

    /** Identifies the version from the {@code openapi} field: {@code major.minor.patch} in ASCII digits, e.g. {@code 3.1.0}. */
    public static Optional<SpecVersion> of(String openapi) {
        var matcher = VERSION.matcher(openapi);
        return matcher.matches() ? java.util.Arrays.stream(values()).filter(v -> v.prefix.equals(matcher.group(1))).findFirst() : Optional.empty();
    }

    /** {@code major.minor}, e.g. {@code 3.1}. */
    public String prefix() {
        return prefix;
    }
}
