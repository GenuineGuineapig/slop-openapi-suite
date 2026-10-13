package dev.openapifn.validation;

import java.util.Objects;

/**
 * A single validation finding.
 *
 * @param key hierarchical issue key, e.g. {@code request.parameter.query.schema.maximum}
 * @param level severity after applying the {@link LevelPolicy}
 * @param message human readable description
 * @param location where the problem is, e.g. {@code query.limit} or {@code body/items/0}
 */
public record ValidationIssue(String key, Level level, String message, String location) {

    public ValidationIssue {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(location, "location");
    }

    @Override
    public String toString() {
        return level + " " + key + (location.isEmpty() ? "" : " [" + location + "]") + ": " + message;
    }
}
