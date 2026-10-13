package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterSerializerTest {

    @Test
    void textForms() {
        assertThat(ParameterSerializer.text(Json.of(1.50))).isEqualTo("1.5");
        assertThat(ParameterSerializer.text(Json.of(new java.math.BigDecimal("1e3")))).isEqualTo("1000");
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
        assertThat(ParameterSerializer.header(ParameterSerializer.Spec.of("h", ParameterLocation.HEADER), Json.of(java.util.List.of(1, 2)))).isEqualTo("1,2");
    }
}
