package dev.openapifn.model;

import java.util.Locale;
import java.util.Optional;

/** Where a parameter is located. */
public enum ParameterLocation {
    PATH,
    QUERY,
    /** The entire query string as a single parameter (3.2). */
    QUERYSTRING,
    HEADER,
    COOKIE;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ParameterLocation> of(String value) {
        for (var location : values()) {
            if (location.value().equals(value)) {
                return Optional.of(location);
            }
        }
        return Optional.empty();
    }

    /** The default style for parameters in this location. */
    public ParameterStyle defaultStyle() {
        return switch (this) {
            case PATH, HEADER -> ParameterStyle.SIMPLE;
            case QUERY, QUERYSTRING, COOKIE -> ParameterStyle.FORM;
        };
    }
}
