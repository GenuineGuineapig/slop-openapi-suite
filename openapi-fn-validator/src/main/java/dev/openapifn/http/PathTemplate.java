package dev.openapifn.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled path template such as {@code /pets/{petId}} or {@code /files/{name}.{ext}}.
 * Template expressions match within a single path segment; matched values stay percent-encoded
 * because their decoding depends on the parameter style.
 */
public final class PathTemplate {

    private static final Pattern EXPRESSION = Pattern.compile("\\{([^}]+)}");

    private final String template;
    private final Pattern pattern;
    private final List<String> names;
    private final int literalLength;

    private PathTemplate(String template, Pattern pattern, List<String> names, int literalLength) {
        this.template = template;
        this.pattern = pattern;
        this.names = names;
        this.literalLength = literalLength;
    }

    public static PathTemplate compile(String template) {
        var regex = new StringBuilder();
        var names = new ArrayList<String>();
        var matcher = EXPRESSION.matcher(template);
        int last = 0;
        int literal = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(last, matcher.start())));
            literal += matcher.start() - last;
            var nextIsSeparator = matcher.end() == template.length() || template.charAt(matcher.end()) == '/';
            regex.append(nextIsSeparator ? "([^/]*)" : "([^/]*?)");
            names.add(matcher.group(1));
            last = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        literal += template.length() - last;
        return new PathTemplate(template, Pattern.compile(regex.toString()), List.copyOf(names), literal);
    }

    public String template() {
        return template;
    }

    public List<String> names() {
        return names;
    }

    /** Matches {@code path} exactly, returning the raw expression values by name. */
    public Optional<Map<String, String>> match(String path) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        var values = new LinkedHashMap<String, String>();
        for (int i = 0; i < names.size(); i++) {
            values.put(names.get(i), matcher.group(i + 1));
        }
        return Optional.of(values);
    }

    /** Matches a prefix of {@code path} ending at a segment boundary, returning the remaining path. */
    public Optional<String> stripPrefix(String path) {
        var prefix = template.endsWith("/") ? template.substring(0, template.length() - 1) : template;
        var matcher = compile(prefix).pattern.matcher(path);
        if (!matcher.lookingAt()) {
            return Optional.empty();
        }
        var rest = path.substring(matcher.end());
        return rest.isEmpty() || rest.startsWith("/") ? Optional.of(rest.isEmpty() ? "/" : rest) : Optional.empty();
    }

    /**
     * Orders templates so that more specific ones come first: fewer expressions, then more
     * literal characters (concrete paths are matched before templated ones).
     */
    public int compareSpecificity(PathTemplate other) {
        var byExpressions = Integer.compare(names.size(), other.names.size());
        return byExpressions != 0 ? byExpressions : Integer.compare(other.literalLength, literalLength);
    }

    @Override
    public String toString() {
        return template;
    }
}
