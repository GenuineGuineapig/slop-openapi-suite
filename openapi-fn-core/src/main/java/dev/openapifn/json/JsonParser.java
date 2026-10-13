package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * A strict RFC 8259 JSON parser. Duplicate object keys keep the last value; nesting deeper than
 * {@link #MAX_DEPTH} is rejected to protect the stack.
 */
public final class JsonParser {

    /** Maximum nesting depth of arrays and objects. */
    public static final int MAX_DEPTH = 512;

    private final String text;
    private int pos;

    private JsonParser(String text) {
        this.text = text;
    }

    /** Parses a complete JSON text. */
    public static Result<JsonValue, JsonSyntaxError> parse(String text) {
        var parser = new JsonParser(text);
        try {
            parser.skipWhitespace();
            var value = parser.readValue(0);
            parser.skipWhitespace();
            if (parser.pos < text.length()) {
                throw parser.error("Unexpected trailing content");
            }
            return Result.ok(value);
        } catch (SyntaxException e) {
            return Result.err(e.error);
        }
    }

    private JsonValue readValue(int depth) {
        if (pos >= text.length()) {
            throw error("Unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth + 1);
            case '[' -> readArray(depth + 1);
            case '"' -> JsonString.of(readString());
            case 't' -> literal("true", JsonBoolean.TRUE);
            case 'f' -> literal("false", JsonBoolean.FALSE);
            case 'n' -> literal("null", JsonNull.INSTANCE);
            default -> {
                if (c == '-' || isDigit(c)) {
                    yield readNumber();
                }
                throw error("Unexpected character '" + c + "'");
            }
        };
    }

    private JsonValue literal(String word, JsonValue value) {
        if (!text.startsWith(word, pos)) {
            throw error("Invalid literal, expected '" + word + "'");
        }
        pos += word.length();
        return value;
    }

    private JsonObject readObject(int depth) {
        checkDepth(depth);
        pos++;
        var members = new LinkedHashMap<String, JsonValue>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return new JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected string object key");
            }
            var key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(key, readValue(depth));
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}') {
                return new JsonObject(members);
            }
            if (c != ',') {
                pos--;
                throw error("Expected ',' or '}' in object");
            }
        }
    }

    private JsonArray readArray(int depth) {
        checkDepth(depth);
        pos++;
        var values = new ArrayList<JsonValue>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return new JsonArray(values);
        }
        while (true) {
            skipWhitespace();
            values.add(readValue(depth));
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']') {
                return new JsonArray(values);
            }
            if (c != ',') {
                pos--;
                throw error("Expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        pos++;
        var out = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("Unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                out.append(readEscape());
            } else if (c < 0x20) {
                pos--;
                throw error("Unescaped control character in string");
            } else {
                out.append(c);
            }
        }
    }

    private char readEscape() {
        if (pos >= text.length()) {
            throw error("Unterminated escape sequence");
        }
        char c = text.charAt(pos++);
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> {
                pos--;
                throw error("Invalid escape character '" + c + "'");
            }
        };
    }

    private char readUnicodeEscape() {
        if (pos + 4 > text.length()) {
            throw error("Truncated unicode escape");
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos + i), 16);
            if (digit < 0) {
                throw error("Invalid unicode escape");
            }
            code = code * 16 + digit;
        }
        pos += 4;
        return (char) code;
    }

    private JsonNumber readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            digits();
        } else {
            throw error("Invalid number");
        }
        if (peek() == '.') {
            pos++;
            if (!isDigit(peek())) {
                throw error("Expected digit after decimal point");
            }
            digits();
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("Expected digit in exponent");
            }
            digits();
        }
        return JsonNumber.of(new BigDecimal(text.substring(start, pos)));
    }

    private void digits() {
        while (isDigit(peek())) {
            pos++;
        }
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("Expected '" + expected + "'");
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("Maximum nesting depth of " + MAX_DEPTH + " exceeded");
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private SyntaxException error(String message) {
        int line = 1;
        int lineStart = 0;
        int end = Math.min(pos, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new SyntaxException(new JsonSyntaxError(message, line, end - lineStart + 1, end));
    }

    private static final class SyntaxException extends RuntimeException {
        private final transient JsonSyntaxError error;

        SyntaxException(JsonSyntaxError error) {
            super(error.message(), null, false, false);
            this.error = error;
        }
    }
}
