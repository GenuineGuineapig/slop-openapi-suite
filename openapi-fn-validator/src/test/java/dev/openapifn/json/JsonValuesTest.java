package dev.openapifn.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValuesTest {

    @Test
    void typeNamesAndAccessors() {
        assertThat(JsonNull.INSTANCE.typeName()).isEqualTo("null");
        assertThat(JsonNull.INSTANCE.isNull()).isTrue();
        assertThat(JsonNull.INSTANCE).hasToString("null");
        assertThat(JsonBoolean.of(true)).isSameAs(JsonBoolean.TRUE);
        assertThat(JsonBoolean.of(false)).isSameAs(JsonBoolean.FALSE);
        assertThat(JsonBoolean.TRUE.typeName()).isEqualTo("boolean");
        assertThat(JsonBoolean.TRUE).hasToString("true");
        assertThat(JsonBoolean.TRUE.isNull()).isFalse();
        assertThat(JsonString.of("a").typeName()).isEqualTo("string");
        assertThat(JsonArray.EMPTY.typeName()).isEqualTo("array");
        assertThat(JsonObject.EMPTY.typeName()).isEqualTo("object");

        JsonValue object = JsonObject.EMPTY;
        JsonValue array = JsonArray.EMPTY;
        JsonValue string = JsonString.of("s");
        JsonValue number = JsonNumber.of(1);
        JsonValue bool = JsonBoolean.TRUE;
        assertThat(object.asObject()).contains(JsonObject.EMPTY);
        assertThat(string.asObject()).isEmpty();
        assertThat(array.asArray()).contains(JsonArray.EMPTY);
        assertThat(string.asArray()).isEmpty();
        assertThat(string.asString()).contains("s");
        assertThat(number.asString()).isEmpty();
        assertThat(number.asNumber()).contains(JsonNumber.of(1));
        assertThat(string.asNumber()).isEmpty();
        assertThat(bool.asBoolean()).contains(true);
        assertThat(string.asBoolean()).isEmpty();
        assertThat(JsonString.of("x\"").toJson()).isEqualTo("\"x\\\"\"");
    }

    @Test
    void numbersCompareNumerically() {
        assertThat(JsonNumber.of(new BigDecimal("1.0"))).isEqualTo(JsonNumber.of(1)).hasSameHashCodeAs(JsonNumber.of(1));
        assertThat(JsonNumber.of(new BigDecimal("0.00"))).isEqualTo(JsonNumber.of(0)).hasSameHashCodeAs(JsonNumber.of(0));
        assertThat(JsonNumber.of(1)).isNotEqualTo(JsonNumber.of(2)).isNotEqualTo("1");
        assertThat(JsonNumber.of(new BigDecimal("1e2")).isIntegral()).isTrue();
        assertThat(JsonNumber.of(new BigDecimal("1.5")).isIntegral()).isFalse();
        assertThat(JsonNumber.of(new BigDecimal("1.5")).typeName()).isEqualTo("number");
        assertThat(JsonNumber.of(3).typeName()).isEqualTo("integer");
        assertThat(JsonNumber.of(1.5)).hasToString("1.5");
        assertThat(JsonNumber.of(BigInteger.TEN)).isEqualTo(JsonNumber.of(10));
        assertThatThrownBy(() -> new JsonNumber(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void strings() {
        assertThat(JsonString.of("a😀").codePointLength()).isEqualTo(2);
        assertThat(JsonString.of("a")).hasToString("\"a\"");
        assertThatThrownBy(() -> new JsonString(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void arrays() {
        var array = JsonArray.of(JsonString.of("a"), JsonNumber.of(1));
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.isEmpty()).isFalse();
        assertThat(JsonArray.EMPTY.isEmpty()).isTrue();
        assertThat(array.get(0)).contains(JsonString.of("a"));
        assertThat(array.get(2)).isEmpty();
        assertThat(array.get(-1)).isEmpty();
        assertThat(array.stream()).hasSize(2);
        assertThat(array).hasToString("[\"a\",1]");
        var source = new ArrayList<JsonValue>(List.of(JsonNull.INSTANCE));
        var copy = new JsonArray(source);
        source.clear();
        assertThat(copy.size()).isEqualTo(1);
    }

    @Test
    void objects() {
        var object = JsonObject.builder()
            .put("s", "text").put("n", 3).put("b", true)
            .put("o", JsonObject.EMPTY).put("a", JsonArray.EMPTY)
            .build();
        assertThat(object.string("s")).contains("text");
        assertThat(object.string("n")).isEmpty();
        assertThat(object.string("missing")).isEmpty();
        assertThat(object.number("n")).contains(JsonNumber.of(3));
        assertThat(object.bool("b")).contains(true);
        assertThat(object.object("o")).contains(JsonObject.EMPTY);
        assertThat(object.array("a")).contains(JsonArray.EMPTY);
        assertThat(object.has("s")).isTrue();
        assertThat(object.has("x")).isFalse();
        assertThat(object.keys()).containsExactly("s", "n", "b", "o", "a");
        assertThat(object.size()).isEqualTo(5);
        assertThat(object.isEmpty()).isFalse();
        assertThat(JsonObject.EMPTY.isEmpty()).isTrue();
        assertThat(object.with("x", JsonNull.INSTANCE).keys()).endsWith("x");
        assertThat(object.without("s").keys()).doesNotContain("s");
        assertThat(JsonObject.builder().put("a", 1).build()).hasToString("{\"a\":1}");
        var nullKey = new LinkedHashMap<String, JsonValue>();
        nullKey.put(null, JsonNull.INSTANCE);
        assertThatThrownBy(() -> new JsonObject(nullKey)).isInstanceOf(NullPointerException.class);
        var nullValue = new LinkedHashMap<String, JsonValue>();
        nullValue.put("a", null);
        assertThatThrownBy(() -> new JsonObject(nullValue)).isInstanceOf(NullPointerException.class);
        assertThat(JsonObject.builder().put("a", 1).put("b", 2).build())
            .isEqualTo(JsonObject.builder().put("b", 2).put("a", 1).build());
    }

    @Test
    void conversionFromJava() {
        var map = new LinkedHashMap<Object, Object>();
        map.put("n", null);
        map.put(1, List.of(true, 1.5d, 2.5f, 3, 4L, (short) 5, new BigDecimal("6.1"), BigInteger.TWO, 'c', new StringBuilder("sb")));
        map.put("arr", new Object[] {"x"});
        map.put("json", JsonString.of("j"));
        var json = Json.of(map);
        assertThat(json.toJson()).isEqualTo("{\"n\":null,\"1\":[true,1.5,2.5,3,4,5,6.1,2,\"c\",\"sb\"],\"arr\":[\"x\"],\"json\":\"j\"}");
        assertThatThrownBy(() -> Json.of(new Object())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("java.lang.Object");
    }

    @Test
    void conversionToJava() {
        var json = Json.parse("{\"a\":[null,true,1,1.5,\"s\",{}]}").orElseThrow(e -> new AssertionError(e));
        var java = Json.toJava(json);
        assertThat(java).isEqualTo(Map.of("a", Arrays.asList(null, true, BigInteger.ONE, new BigDecimal("1.5"), "s", Map.of())));
        assertThat(Json.toJava(JsonArray.of(JsonNull.INSTANCE))).isInstanceOf(ArrayList.class);
        assertThat(Json.toJava(JsonObject.EMPTY)).isInstanceOf(LinkedHashMap.class);
    }
}
