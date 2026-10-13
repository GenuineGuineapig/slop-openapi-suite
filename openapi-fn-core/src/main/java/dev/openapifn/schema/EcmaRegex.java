package dev.openapifn.schema;

import dev.openapifn.fn.Result;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles ECMA-262 regular expressions (as used by JSON Schema) into Java patterns. The
 * dialects are largely compatible; the translation covers the differences that matter in
 * practice: {@code \d}, {@code \w} and {@code \s} semantics, {@code [^]}, {@code $} at the end of
 * input, and {@code \p{...}} long property names.
 */
public final class EcmaRegex {

    private static final Map<String, Result<Pattern, String>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern GLOBAL_FLAGS = Pattern.compile("\\(\\?[a-zA-Z-]+\\)");

    private EcmaRegex() {
    }

    /** Compiles (and caches) {@code regex}; the error describes why it is invalid. */
    public static Result<Pattern, String> compile(String regex) {
        return CACHE.computeIfAbsent(regex, EcmaRegex::translate);
    }

    private static Result<Pattern, String> translate(String regex) {
        var out = new StringBuilder(regex.length() + 16);
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                char next = regex.charAt(i + 1);
                i++;
                switch (next) {
                    case 'v' -> out.append("\\x0B");
                    case '0' -> out.append(i + 1 < regex.length() && Character.isDigit(regex.charAt(i + 1)) ? "\\0" : "\\x00");
                    case 'd' -> out.append(inClass ? "0-9" : "[0-9]");
                    case 'D' -> out.append(inClass ? "\\x00-/:-\\x{10FFFF}" : "[^0-9]");
                    case 'w' -> out.append(inClass ? "a-zA-Z0-9_" : "[a-zA-Z0-9_]");
                    case 'W' -> out.append(inClass ? "\\x00-/:-@\\[-^`{-\\x{10FFFF}" : "[^a-zA-Z0-9_]");
                    case 's' -> out.append(inClass ? WHITESPACE : "[" + WHITESPACE + "]");
                    case 'S' -> out.append(inClass ? "\\S" : "[^" + WHITESPACE + "]");
                    case 'p', 'P' -> i = property(regex, i, next, out);
                    default -> {
                        if (Character.isLetter(next) && "bBfnrtcxuk".indexOf(next) < 0) {
                            return Result.err("Invalid regular expression '" + regex + "': invalid escape \\" + next);
                        }
                        out.append(c).append(next);
                    }
                }
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                out.append(c);
            } else if (c == '[' && regex.startsWith("[^]", i)) {
                out.append("[\\s\\S]");
                i += 2;
            } else if (c == '[' && regex.startsWith("[]", i)) {
                out.append("(?!)");
                i += 1;
            } else if (c == '(' && GLOBAL_FLAGS.matcher(regex).region(i, regex.length()).lookingAt()) {
                return Result.err("Invalid regular expression '" + regex + "': inline flag groups are not supported");
            } else {
                inClass = c == '[';
                out.append(c == '$' ? "\\z" : String.valueOf(c));
            }
        }
        try {
            return Result.ok(Pattern.compile(out.toString()));
        } catch (PatternSyntaxException e) {
            return Result.err("Invalid regular expression '" + regex + "': " + e.getDescription());
        }
    }

    private static final String WHITESPACE = "\\t\\n\\x0B\\f\\r \\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}";

    private static int property(String regex, int index, char kind, StringBuilder out) {
        int open = index + 1;
        int close = regex.indexOf('}', open);
        if (open >= regex.length() || regex.charAt(open) != '{' || close < 0) {
            out.append('\\').append(kind);
            return index;
        }
        var name = regex.substring(open + 1, close);
        var eq = name.indexOf('=');
        var value = eq >= 0 ? name.substring(eq + 1) : name;
        var key = eq >= 0 ? name.substring(0, eq) : "";
        var translated = switch (key) {
            case "Script", "sc", "Script_Extensions", "scx" -> "Is" + value;
            case "General_Category", "gc" -> generalCategory(value);
            default -> eq >= 0 ? name : generalCategory(value);
        };
        out.append('\\').append(kind).append('{').append(translated).append('}');
        return close;
    }

    private static String generalCategory(String value) {
        return switch (value) {
            case "Letter" -> "L";
            case "Lowercase_Letter" -> "Ll";
            case "Uppercase_Letter" -> "Lu";
            case "Number" -> "N";
            case "Decimal_Number", "digit" -> "Nd";
            case "Punctuation", "punct" -> "P";
            case "Symbol" -> "S";
            case "Separator" -> "Z";
            case "Mark" -> "M";
            case "Other" -> "C";
            default -> value;
        };
    }
}
