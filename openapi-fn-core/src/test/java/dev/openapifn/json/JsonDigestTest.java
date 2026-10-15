package dev.openapifn.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonDigestTest {

    private static JsonDigest digest(String json) {
        return JsonDigest.of(Json.parse(json).orElseThrow(e -> new AssertionError(e.toString())));
    }

    @Test
    void equalValuesHaveEqualDigests() {
        assertThat(digest("1")).isEqualTo(digest("1.0")).isEqualTo(digest("1e0")).isEqualTo(digest("0.1e1"));
        assertThat(digest("0")).isEqualTo(digest("-0.0"));
        assertThat(digest("{\"a\": 1, \"b\": [true, null]}")).isEqualTo(digest("{\"b\": [true, null], \"a\": 1.00}"));
        assertThat(digest("\"x\"")).isEqualTo(digest("\"x\""));
    }

    @Test
    void differentValuesHaveDifferentDigests() {
        var values = List.of("null", "true", "false", "1", "10", "0.1", "\"1\"", "\"\"", "[]", "{}", "[1]", "[[1]]", "[1, 2]", "[2, 1]",
            "{\"a\": 1}", "{\"a\": 2}", "{\"b\": 1}", "{\"a\": 1, \"b\": 1}", "[\"a\", \"b\"]", "[\"ab\"]", "{\"ab\": \"\"}", "{\"a\": \"b\"}");
        assertThat(values.stream().map(JsonDigestTest::digest).distinct()).hasSize(values.size());
    }
}
