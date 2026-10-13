package dev.openapifn.validation;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides the level of each issue key. Overrides are matched by key prefix on {@code .}
 * boundaries, longest first, so {@code request.body} covers {@code request.body.schema.required}.
 *
 * @param defaultLevel level of keys without an override
 * @param overrides levels by key prefix
 */
public record LevelPolicy(Level defaultLevel, Map<String, Level> overrides) {

    /**
     * Errors everywhere except unknown query parameters (ignored) and unexpected bodies
     * (warnings).
     */
    public static final LevelPolicy DEFAULT = new LevelPolicy(Level.ERROR, Map.of(
        IssueKeys.REQUEST_QUERY_UNKNOWN, Level.IGNORE,
        IssueKeys.REQUEST_BODY_UNEXPECTED, Level.WARN,
        IssueKeys.RESPONSE_BODY_UNEXPECTED, Level.WARN));

    public LevelPolicy {
        overrides = Map.copyOf(overrides);
    }

    /** A copy where {@code keyPrefix} (and everything below it) has {@code level}. */
    public LevelPolicy with(String keyPrefix, Level level) {
        var copy = new HashMap<>(overrides);
        copy.put(keyPrefix, level);
        return new LevelPolicy(defaultLevel, copy);
    }

    public Level levelFor(String key) {
        for (var candidate = key; ; candidate = candidate.substring(0, candidate.lastIndexOf('.'))) {
            var level = overrides.get(candidate);
            if (level != null) {
                return level;
            }
            if (candidate.indexOf('.') < 0) {
                return defaultLevel;
            }
        }
    }
}
