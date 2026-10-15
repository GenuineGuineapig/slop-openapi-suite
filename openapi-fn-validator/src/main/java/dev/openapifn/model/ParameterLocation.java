package dev.openapifn.model;

import java.util.Arrays;
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

    private final String value = name().toLowerCase(Locale.ROOT);

    /** The name in descriptions: {@code path}, {@code query}, … */
    public String value() {
        return value;
    }

    public static Optional<ParameterLocation> of(String value) {
        return Arrays.stream(values()).filter(location -> location.value.equals(value)).findFirst();
    }

    /** The default style for parameters in this location. */
    public ParameterStyle defaultStyle() {
        return switch (this) {
            case PATH, HEADER -> ParameterStyle.SIMPLE;
            case QUERY, QUERYSTRING, COOKIE -> ParameterStyle.FORM;
        };
    }
}
