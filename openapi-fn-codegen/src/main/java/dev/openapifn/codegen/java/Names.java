package dev.openapifn.codegen.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Turns arbitrary names from API descriptions into valid, conventional Java identifiers. */
public final class Names {

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "var", "record", "yield", "sealed", "permits", "non-sealed", "_");

    private Names() {
    }

    /** Words of {@code name}, split at non-alphanumerics and lower-to-upper case transitions. */
    static List<String> words(String name) {
        var words = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(words, current);
                continue;
            }
            var boundary = current.length() > 0 && Character.isUpperCase(c)
                && (Character.isLowerCase(current.charAt(current.length() - 1))
                    || (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)) && Character.isUpperCase(current.charAt(current.length() - 1))));
            if (boundary) {
                flush(words, current);
            }
            current.append(c);
        }
        flush(words, current);
        return words;
    }

    private static void flush(List<String> words, StringBuilder current) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    /** {@code PascalCase}, e.g. {@code pet_store} → {@code PetStore}. */
    public static String pascal(String name) {
        var out = new StringBuilder();
        for (var word : words(name)) {
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return identifier(out.isEmpty() ? "Unnamed" : out.toString());
    }

    /** {@code camelCase}, e.g. {@code X-Request-Id} → {@code xRequestId}. */
    public static String camel(String name) {
        var words = words(name);
        if (words.isEmpty()) {
            return "unnamed";
        }
        var out = new StringBuilder(words.getFirst().toLowerCase(Locale.ROOT));
        for (var word : words.subList(1, words.size())) {
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return identifier(out.toString());
    }

    /** {@code UPPER_SNAKE_CASE}, e.g. {@code someValue} → {@code SOME_VALUE}. */
    public static String constant(String name) {
        var words = words(name);
        var joined = String.join("_", words.stream().map(word -> word.toUpperCase(Locale.ROOT)).toList());
        return identifier(joined.isEmpty() ? "EMPTY" : joined);
    }

    /** {@code name} made a legal identifier: invalid starts get {@code _}, keywords a trailing {@code _}. */
    public static String identifier(String name) {
        var valid = Character.isJavaIdentifierStart(name.charAt(0)) ? name : "_" + name;
        return KEYWORDS.contains(valid) ? valid + "_" : valid;
    }

    /** {@code base}, or {@code base2}, {@code base3}... whichever is not in {@code taken}; the result is added. */
    public static String unique(String base, Set<String> taken) {
        var candidate = base;
        for (int i = 2; !taken.add(candidate); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    /** A Java string literal for {@code value}. */
    public static String literal(String value) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 || (c >= 0x7f && c < 0xa0) ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    /** Javadoc text: comment terminators and line-leading {@code @} (which would start a block tag) are neutralized. */
    public static String javadoc(String text) {
        return text.replace("*/", "*&#47;").replaceAll("(?m)^(\\s*)@", "$1&#64;").strip();
    }
}
