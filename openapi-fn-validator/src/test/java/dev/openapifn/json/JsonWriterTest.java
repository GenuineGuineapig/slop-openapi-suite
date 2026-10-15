package dev.openapifn.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void writesPretty() {
        var value = JsonObject.builder()
            .put("a", JsonArray.of(JsonNumber.of(1), JsonBoolean.FALSE))
            .put("b", JsonObject.EMPTY)
            .put("c", JsonArray.EMPTY)
            .put("d", JsonNull.INSTANCE)
            .build();
        assertThat(JsonWriter.pretty(value)).isEqualTo("""
            {
              "a": [
                1,
                false
              ],
              "b": {},
              "c": [],
              "d": null
            }""");
    }

    @Test
    void escapesStrings() {
        assertThat(JsonWriter.quote("\"\\\n\r\t\b\f\u0001é")).isEqualTo("\"\\\"\\\\\\n\\r\\t\\b\\f\\u0001é\"");
    }

    @Test
    void roundTrips() {
        var text = "{\"a\":[1,2.5,\"x\",{\"y\":null}],\"b\":true}";
        assertThat(JsonWriter.compact(Json.parse(text).orElseThrow(e -> new AssertionError(e)))).isEqualTo(text);
    }
}
