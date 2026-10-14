package dev.openapifn.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonParserTest {

    private static JsonValue parse(String text) {
        return JsonParser.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    private static JsonSyntaxError fail(String text) {
        return JsonParser.parse(text).failure().orElseThrow();
    }

    @Test
    void parsesAllValueKinds() {
        var value = parse(" {\"a\" : [1, -2.5e3, 0, 1E+2, 3e-1, true, false, null, \"x\"], \"b\": {}, \"c\": [], \"d\": {\"e\": 1}}\r\n\t");
        assertThat(value.toJson()).isEqualTo("{\"a\":[1,-2.5E+3,0,1E+2,0.3,true,false,null,\"x\"],\"b\":{},\"c\":[],\"d\":{\"e\":1}}");
        assertThat(parse("0.25")).isEqualTo(JsonNumber.of(new BigDecimal("0.25")));
    }

    @Test
    void parsesEscapes() {
        assertThat(parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\uD83D\\uDE00\"")).isEqualTo(JsonString.of("\"\\/\b\f\n\r\té😀"));
        assertThat(parse("\"\\u00AF\"")).isEqualTo(JsonString.of("\u00af"));
    }

    @Test
    void duplicateKeysKeepLastValue() {
        assertThat(parse("{\"a\":1,\"a\":2}")).isEqualTo(JsonObject.builder().put("a", 2).build());
    }

    /** Jackson's messages; the tests pin a distinctive part of each and the position. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|Unexpected end of input|1|1",
        "'{\"a\":1} x'|Unrecognized token 'x'|1|9",
        "'[1] [2]'|Unexpected trailing content|1|5",
        "'tru'|Unrecognized token 'tru'|1|1",
        "'@'|Unexpected character ('@' (code 64)): expected a valid value|1|1",
        "'{1:2}'|was expecting double-quote to start property name|1|2",
        "'{\"a\" 1}'|was expecting a colon to separate property name and value|1|6",
        "'{\"a\":1 \"b\"}'|was expecting comma to separate Object entries|1|8",
        "'[1 2]'|was expecting comma to separate Array entries|1|4",
        "'\"abc'|was expecting closing quote for a string value|1|5",
        "'\"\\'|Unexpected end-of-input in character escape sequence|1|3",
        "'\"\\x\"'|Unrecognized character escape 'x'|1|3",
        "'\"\\u12g4\"'|expected a hex-digit for character escape sequence|1|6",
        "'-a'|expected digit (0-9) to follow minus sign|1|2",
        "'1.'|Decimal point not followed by a digit|1|2",
        "'1e+'|expected a digit for number exponent|1|4",
        "'01'|Leading zeroes not allowed|1|2",
        "'[\n1,\n@]'|Unexpected character ('@'|3|1",
    })
    void reportsSyntaxErrors(String text, String message, int line, int column) {
        var error = fail(text);
        assertThat(error.message()).contains(message);
        assertThat(error.line()).isEqualTo(line);
        assertThat(error.column()).isEqualTo(column);
        assertThat(error.toString()).isEqualTo(error.message() + " at line " + line + ", column " + column);
    }

    @Test
    void rejectsControlCharactersInStrings() {
        assertThat(fail("\"a\u0001\"").message()).startsWith("Illegal unquoted character");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "{\"a\":"})
    void rejectsExcessiveNesting(String open) {
        assertThat(fail(open.repeat(JsonParser.MAX_DEPTH + 1)).message()).startsWith("Document nesting depth (513) exceeds the maximum allowed (512");
        assertThat(parse("[".repeat(JsonParser.MAX_DEPTH) + "]".repeat(JsonParser.MAX_DEPTH))).isInstanceOf(JsonArray.class);
    }

    @Test
    void rejectsOverlongNumbers() {
        var error = fail("1".repeat(1100));
        assertThat(error.message()).startsWith("Number value length (1100) exceeds the maximum allowed (1000");
        assertThat(error.column()).isEqualTo(1101);
    }
}
