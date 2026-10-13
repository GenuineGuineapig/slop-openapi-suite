package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** An RFC 6901 JSON Pointer. */
public record JsonPointer(List<String> tokens) {

    public static final JsonPointer ROOT = new JsonPointer(List.of());

    private static final String FRAGMENT_SAFE =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@/?";

    public JsonPointer {
        tokens = List.copyOf(tokens);
    }

    public static JsonPointer of(String... tokens) {
        return new JsonPointer(List.of(tokens));
    }

    /** Parses the string form, e.g. {@code /paths/~1pets/get}. */
    public static Result<JsonPointer, String> parse(String pointer) {
        if (pointer.isEmpty()) {
            return Result.ok(ROOT);
        }
        if (pointer.charAt(0) != '/') {
            return Result.err("JSON pointer must be empty or start with '/': " + pointer);
        }
        var tokens = new ArrayList<String>();
        for (var raw : pointer.substring(1).split("/", -1)) {
            var token = unescape(raw);
            if (token == null) {
                return Result.err("Invalid escape sequence in JSON pointer: " + pointer);
            }
            tokens.add(token);
        }
        return Result.ok(new JsonPointer(tokens));
    }

    private static String unescape(String raw) {
        var out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '~') {
                out.append(c);
                continue;
            }
            char next = i + 1 < raw.length() ? raw.charAt(i + 1) : 'x';
            if (next == '0') {
                out.append('~');
            } else if (next == '1') {
                out.append('/');
            } else {
                return null;
            }
            i++;
        }
        return out.toString();
    }

    public JsonPointer child(String token) {
        var next = new ArrayList<>(tokens);
        next.add(token);
        return new JsonPointer(next);
    }

    public JsonPointer child(int index) {
        return child(Integer.toString(index));
    }

    public JsonPointer append(JsonPointer suffix) {
        var next = new ArrayList<>(tokens);
        next.addAll(suffix.tokens);
        return new JsonPointer(next);
    }

    public boolean isRoot() {
        return tokens.isEmpty();
    }

    public Optional<JsonPointer> parent() {
        return isRoot() ? Optional.empty() : Optional.of(new JsonPointer(tokens.subList(0, tokens.size() - 1)));
    }

    public Optional<String> last() {
        return isRoot() ? Optional.empty() : Optional.of(tokens.getLast());
    }

    public boolean startsWith(JsonPointer prefix) {
        return tokens.size() >= prefix.tokens.size() && tokens.subList(0, prefix.tokens.size()).equals(prefix.tokens);
    }

    /** Evaluates this pointer against {@code root}. */
    public Optional<JsonValue> resolve(JsonValue root) {
        JsonValue current = root;
        for (var token : tokens) {
            var next = switch (current) {
                case JsonObject object -> object.get(token);
                case JsonArray array -> arrayIndex(token).flatMap(array::get);
                default -> Optional.<JsonValue>empty();
            };
            if (next.isEmpty()) {
                return Optional.empty();
            }
            current = next.get();
        }
        return Optional.of(current);
    }

    private static Optional<Integer> arrayIndex(String token) {
        if (token.isEmpty() || token.length() > 9 || (token.length() > 1 && token.charAt(0) == '0')) {
            return Optional.empty();
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(Integer.parseInt(token));
    }

    /** The pointer percent-encoded for use as a URI fragment, including the leading {@code #}. */
    public String toUriFragment() {
        var out = new StringBuilder("#");
        for (byte b : toString().getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && FRAGMENT_SAFE.indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    @Override
    public String toString() {
        var out = new StringBuilder();
        for (var token : tokens) {
            out.append('/').append(token.replace("~", "~0").replace("/", "~1"));
        }
        return out.toString();
    }
}
