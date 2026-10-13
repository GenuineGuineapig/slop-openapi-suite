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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|Unexpected end of input|1|1",
        "'{\"a\":1} x'|Unexpected trailing content|1|9",
        "'tru'|Invalid literal, expected 'true'|1|1",
        "'fals'|Invalid literal, expected 'false'|1|1",
        "'nul'|Invalid literal, expected 'null'|1|1",
        "'@'|Unexpected character '@'|1|1",
        "'{1:2}'|Expected string object key|1|2",
        "'{\"a\" 1}'|Expected ':'|1|6",
        "'{\"a\":1 \"b\"}'|Expected ',' or '}' in object|1|8",
        "'[1 2]'|Expected ',' or ']' in array|1|4",
        "'\"abc'|Unterminated string|1|5",
        "'\"\\'|Unterminated escape sequence|1|3",
        "'\"\\x\"'|Invalid escape character 'x'|1|3",
        "'\"\\u12\"'|Truncated unicode escape|1|4",
        "'\"\\u12g4\"'|Invalid unicode escape|1|4",
        "'-'|Invalid number|1|2",
        "'-a'|Invalid number|1|2",
        "'1.'|Expected digit after decimal point|1|3",
        "'1e'|Expected digit in exponent|1|3",
        "'1e+'|Expected digit in exponent|1|4",
        "'[\n1,\n@]'|Unexpected character '@'|3|1",
    })
    void reportsSyntaxErrors(String text, String message, int line, int column) {
        var error = fail(text);
        assertThat(error.message()).isEqualTo(message);
        assertThat(error.line()).isEqualTo(line);
        assertThat(error.column()).isEqualTo(column);
        assertThat(error.toString()).isEqualTo(message + " at line " + line + ", column " + column);
    }

    @Test
    void rejectsControlCharactersInStrings() {
        assertThat(fail("\"a\u0001\"").message()).isEqualTo("Unescaped control character in string");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[", "{\"a\":"})
    void rejectsExcessiveNesting(String open) {
        assertThat(fail(open.repeat(JsonParser.MAX_DEPTH + 1)).message()).startsWith("Maximum nesting depth");
    }

    @Test
    void leadingZeroIsTrailingContent() {
        assertThat(fail("01").message()).isEqualTo("Unexpected trailing content");
    }
}
