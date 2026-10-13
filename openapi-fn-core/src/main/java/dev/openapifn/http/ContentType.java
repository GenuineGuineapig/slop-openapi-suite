package dev.openapifn.http;

import dev.openapifn.fn.Immutable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A parsed media type or media range such as {@code application/json; charset=utf-8} or
 * {@code text/*}.
 *
 * @param type lower-cased type, possibly {@code *}
 * @param subtype lower-cased subtype, possibly {@code *}
 * @param parameters parameters with lower-cased names
 */
public record ContentType(String type, String subtype, Map<String, String> parameters) {

    public ContentType {
        parameters = Immutable.map(parameters);
    }

    /** Parses a media type; returns empty for malformed input. */
    public static Optional<ContentType> parse(String text) {
        var parts = text.split(";");
        var essence = parts[0].trim().toLowerCase(Locale.ROOT);
        var slash = essence.indexOf('/');
        if (slash <= 0 || slash == essence.length() - 1 || essence.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        var parameters = new LinkedHashMap<String, String>();
        for (int i = 1; i < parts.length; i++) {
            var eq = parts[i].indexOf('=');
            if (eq > 0) {
                var value = parts[i].substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                parameters.put(parts[i].substring(0, eq).trim().toLowerCase(Locale.ROOT), value);
            }
        }
        return Optional.of(new ContentType(essence.substring(0, slash), essence.substring(slash + 1), parameters));
    }

    /** {@code type/subtype} without parameters. */
    public String essence() {
        return type + "/" + subtype;
    }

    public Optional<String> parameter(String name) {
        return Optional.ofNullable(parameters.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Whether this is JSON: {@code application/json} or any {@code +json} structured syntax suffix. */
    public boolean isJson() {
        return subtype.equals("json") || subtype.endsWith("+json");
    }

    /** Whether this is XML: {@code application/xml}, {@code text/xml} or any {@code +xml} suffix. */
    public boolean isXml() {
        return subtype.equals("xml") || subtype.endsWith("+xml");
    }

    /**
     * Whether this media range includes {@code other}: wildcards match anything, and parameters
     * of the range must be present with equal values.
     */
    public boolean includes(ContentType other) {
        var typeMatches = type.equals("*") || type.equals(other.type);
        var subtypeMatches = subtype.equals("*") || subtype.equals(other.subtype)
            || (subtype.startsWith("*+") && other.subtype.endsWith(subtype.substring(1)));
        return typeMatches && subtypeMatches && parameters.entrySet().stream()
            .allMatch(p -> p.getValue().equalsIgnoreCase(other.parameters.getOrDefault(p.getKey(), "")));
    }

    /** Higher is more specific: exact types beat {@code type/*}, which beats {@code *}{@code /*}; parameters add precision. */
    public int specificity() {
        var base = type.equals("*") ? 0 : subtype.equals("*") ? 1 : subtype.startsWith("*+") ? 2 : 3;
        return base * 100 + parameters.size();
    }

    /** The most specific of {@code ranges} (media range keys of a content map) that includes {@code actual}. */
    public static Optional<String> bestMatch(Collection<String> ranges, ContentType actual) {
        String best = null;
        int bestScore = -1;
        for (var range : ranges) {
            var parsed = parse(range);
            if (parsed.isPresent() && parsed.get().includes(actual) && parsed.get().specificity() > bestScore) {
                best = range;
                bestScore = parsed.get().specificity();
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public String toString() {
        var out = new StringBuilder(essence());
        parameters.forEach((name, value) -> out.append("; ").append(name).append('=').append(value));
        return out.toString();
    }
}
