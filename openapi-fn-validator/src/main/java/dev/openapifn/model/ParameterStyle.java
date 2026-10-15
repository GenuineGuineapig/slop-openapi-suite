package dev.openapifn.model;

import java.util.Optional;

/** Parameter serialization styles. */
public enum ParameterStyle {
    MATRIX("matrix"),
    LABEL("label"),
    SIMPLE("simple"),
    FORM("form"),
    SPACE_DELIMITED("spaceDelimited"),
    PIPE_DELIMITED("pipeDelimited"),
    DEEP_OBJECT("deepObject"),
    /** Cookie style (3.2): like {@code form}, but without percent-encoding and with {@code ; } separators. */
    COOKIE("cookie");

    private final String value;

    ParameterStyle(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ParameterStyle> of(String value) {
        for (var style : values()) {
            if (style.value.equals(value)) {
                return Optional.of(style);
            }
        }
        return Optional.empty();
    }

    /** Whether {@code explode} defaults to true for this style. */
    public boolean explodeByDefault() {
        return this == FORM || this == COOKIE;
    }
}
