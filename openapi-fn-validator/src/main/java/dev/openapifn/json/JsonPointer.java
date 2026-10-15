package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An RFC 6901 JSON Pointer: an immutable, persistent list of reference tokens. A child shares its
 * parent, so {@link #child} takes constant time however deep the pointer is (validation makes one
 * for every member it visits), and the hash code and the string form are computed once.
 */
public final class JsonPointer {

    public static final JsonPointer ROOT = new JsonPointer(null, "", 0);

    private static final String FRAGMENT_SAFE =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@/?";

    /** The pointer without the last token; {@code null} for the root only. */
    private final JsonPointer parent;
    private final String token;
    private final int depth;
    /** Computed when first asked for (racy but idempotent, as {@link String#hashCode} is); 0 until then. */
    private int hash;
    private String text;

    private JsonPointer(JsonPointer parent, String token, int depth) {
        this.parent = parent;
        this.token = token;
        this.depth = depth;
    }

    public static JsonPointer of(String... tokens) {
        return of(Arrays.asList(tokens));
    }

    public static JsonPointer of(List<String> tokens) {
        var pointer = ROOT;
        for (var token : tokens) {
            pointer = pointer.child(token);
        }
        return pointer;
    }

    /** Parses the string form, e.g. {@code /paths/~1pets/get}. */
    public static Result<JsonPointer, String> parse(String pointer) {
        if (pointer.isEmpty()) {
            return Result.ok(ROOT);
        }
        if (pointer.charAt(0) != '/') {
            return Result.err("JSON pointer must be empty or start with '/': " + pointer);
        }
        var parsed = ROOT;
        for (var raw : pointer.substring(1).split("/", -1)) {
            var token = unescape(raw);
            if (token.isEmpty()) {
                return Result.err("Invalid escape sequence in JSON pointer: " + pointer);
            }
            parsed = parsed.child(token.get());
        }
        return Result.ok(parsed);
    }

    private static Optional<String> unescape(String raw) {
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
                return Optional.empty();
            }
            i++;
        }
        return Optional.of(out.toString());
    }

    /** The reference tokens, from the root on. */
    public List<String> tokens() {
        var tokens = new String[depth];
        for (var pointer = this; pointer.depth > 0; pointer = pointer.parent) {
            tokens[pointer.depth - 1] = pointer.token;
        }
        return List.of(tokens);
    }

    public JsonPointer child(String token) {
        return new JsonPointer(this, Objects.requireNonNull(token, "token"), depth + 1);
    }

    public JsonPointer child(int index) {
        return child(Integer.toString(index));
    }

    public JsonPointer append(JsonPointer suffix) {
        var pointer = this;
        for (var token : suffix.tokens()) {
            pointer = pointer.child(token);
        }
        return pointer;
    }

    public boolean isRoot() {
        return depth == 0;
    }

    public Optional<JsonPointer> parent() {
        return Optional.ofNullable(parent);
    }

    public Optional<String> last() {
        return isRoot() ? Optional.empty() : Optional.of(token);
    }

    public boolean startsWith(JsonPointer prefix) {
        var ancestor = this;
        while (ancestor.depth > prefix.depth) {
            ancestor = ancestor.parent;
        }
        return ancestor.equals(prefix);
    }

    /** Evaluates this pointer against {@code root}. */
    public Optional<JsonValue> resolve(JsonValue root) {
        JsonValue current = root;
        for (var token : tokens()) {
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

    /** Pointers are equal when their tokens are. */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof JsonPointer that) || depth != that.depth || hashCode() != that.hashCode()) {
            return false;
        }
        for (JsonPointer left = this, right = that; left != right; left = left.parent, right = right.parent) {
            if (!left.token.equals(right.token)) {
                return false;
            }
        }
        return true;
    }

    /** The hash code of {@link #tokens()} as a {@link List}. */
    @Override
    public int hashCode() {
        var h = hash;
        if (h == 0 && depth > 0) {
            h = 31 * parent.hashCode() + token.hashCode();
            hash = h;
        }
        return depth == 0 ? 1 : h;
    }

    @Override
    public String toString() {
        var t = text;
        if (t == null) {
            t = isRoot() ? "" : parent + "/" + token.replace("~", "~0").replace("/", "~1");
            text = t;
        }
        return t;
    }
}
