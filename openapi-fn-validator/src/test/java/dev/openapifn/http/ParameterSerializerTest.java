package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterSerializerTest {

    @Test
    void textForms() {
        assertThat(ParameterSerializer.text(Json.of(1.50))).isEqualTo("1.5");
        assertThat(ParameterSerializer.text(Json.of(new BigDecimal("1e3")))).isEqualTo("1000");
        assertThat(ParameterSerializer.text(Json.of(true))).isEqualTo("true");
        assertThat(ParameterSerializer.text(Json.of(null))).isEmpty();
        assertThat(ParameterSerializer.text(Json.of(Map.of("a", 1)))).isEqualTo("{\"a\":1}");
    }

    @Test
    void specsAndEncoding() {
        var spec = ParameterSerializer.Spec.of("q", ParameterLocation.QUERY);
        assertThat(spec.style()).isEqualTo(ParameterStyle.FORM);
        assertThat(spec.explode()).isTrue();
        assertThat(ParameterSerializer.query(spec, Json.of("a b/c"))).containsExactly(Map.entry("q", "a%20b%2Fc"));
        var reserved = new ParameterSerializer.Spec("q", ParameterLocation.QUERY, ParameterStyle.FORM, true, true);
        assertThat(ParameterSerializer.query(reserved, Json.of("a/b:c&d"))).containsExactly(Map.entry("q", "a/b:c%26d"));
        var deep = new ParameterSerializer.Spec("q", ParameterLocation.QUERY, ParameterStyle.DEEP_OBJECT, true, false);
        assertThat(ParameterSerializer.query(deep, Json.of("x"))).containsExactly(Map.entry("q", "x"));
        assertThat(ParameterSerializer.path(ParameterSerializer.Spec.of("p", ParameterLocation.PATH), Json.of("a/b"))).isEqualTo("a%2Fb");
        assertThat(ParameterSerializer.header(ParameterSerializer.Spec.of("h", ParameterLocation.HEADER), Json.of(List.of(1, 2)))).isEqualTo("1,2");
    }

    @Test
    void emptyMatrixValuesAreTheNameAlone() {
        var matrix = new ParameterSerializer.Spec("m", ParameterLocation.PATH, ParameterStyle.MATRIX, false, false);
        var exploded = new ParameterSerializer.Spec("m", ParameterLocation.PATH, ParameterStyle.MATRIX, true, false);
        assertThat(ParameterSerializer.path(matrix, Json.of(""))).isEqualTo(";m");
        assertThat(ParameterSerializer.path(exploded, Json.of(List.of("a", "")))).isEqualTo(";m=a;m");
        assertThat(ParameterSerializer.path(exploded, Json.of(Map.of("k", "")))).isEqualTo(";k");
        assertThat(ParameterSerializer.path(exploded, Json.of(Map.of("k", "v")))).isEqualTo(";k=v");
        assertThat(ParameterSerializer.path(matrix, Json.of(List.of()))).isEqualTo(";m");
    }

    @Test
    void parametersDescribedByContentAreSerializedInTheirMediaType() {
        var json = Optional.of("application/json");
        var value = Json.of(Map.of("a", "b c"));
        var path = new ParameterSerializer.Spec("p", ParameterLocation.PATH, ParameterStyle.SIMPLE, false, false, Optional.empty(), json);
        assertThat(ParameterSerializer.path(path, value)).isEqualTo("%7B%22a%22%3A%22b%20c%22%7D");
        var header = new ParameterSerializer.Spec("h", ParameterLocation.HEADER, ParameterStyle.SIMPLE, false, false, Optional.empty(), json);
        assertThat(ParameterSerializer.header(header, value)).isEqualTo("{\"a\":\"b c\"}");
        var cookie = new ParameterSerializer.Spec("c", ParameterLocation.COOKIE, ParameterStyle.FORM, false, false, Optional.empty(), json);
        assertThat(ParameterSerializer.cookie(cookie, value)).containsExactly(Map.entry("c", "%7B%22a%22%3A%22b%20c%22%7D"));
        var form = new ParameterSerializer.Spec("q", ParameterLocation.QUERY, ParameterStyle.FORM, true, false, Optional.empty(),
            Optional.of("application/x-www-form-urlencoded"));
        assertThat(ParameterSerializer.query(form, value)).containsExactly(Map.entry("q", "a%3Db%2Bc"));
        assertThat(ParameterSerializer.query(form, Json.of("a=1"))).containsExactly(Map.entry("q", "a%3D1"));
        var text = new ParameterSerializer.Spec("q", ParameterLocation.QUERY, ParameterStyle.FORM, true, false, Optional.empty(),
            Optional.of("text/plain"));
        assertThat(ParameterSerializer.query(text, Json.of(1))).containsExactly(Map.entry("q", "1"));
        // Query strings: forms (and strings) as they are, other media types percent-encoded.
        var bare = ParameterSerializer.Spec.of("qs", ParameterLocation.QUERYSTRING);
        assertThat(ParameterSerializer.querystring(bare, Json.of("a=1&b"))).isEqualTo("a=1&b");
        assertThat(ParameterSerializer.querystring(bare, value)).isEqualTo("a=b+c");
        var jsonQuery = new ParameterSerializer.Spec("qs", ParameterLocation.QUERYSTRING, ParameterStyle.FORM, true, false, Optional.empty(), json);
        assertThat(ParameterSerializer.querystring(jsonQuery, value)).isEqualTo("%7B%22a%22%3A%22b%20c%22%7D");
    }
}
