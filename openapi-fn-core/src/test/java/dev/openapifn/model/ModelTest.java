package dev.openapifn.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.fn.Immutable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelTest {

    private static Parameter parameter(String name, ParameterLocation in) {
        return new Parameter(name, in, Optional.empty(), false, false, false, in.defaultStyle(), in.defaultStyle().explodeByDefault(), false,
            Optional.empty(), Optional.empty(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void specVersions() {
        assertThat(SpecVersion.of("3.1")).contains(SpecVersion.V3_1);
        assertThat(SpecVersion.of("3.0.4")).contains(SpecVersion.V3_0);
        assertThat(SpecVersion.of("3.2.0")).contains(SpecVersion.V3_2);
        assertThat(SpecVersion.of("3.10.0")).isEmpty();
        assertThat(SpecVersion.of("2.0")).isEmpty();
    }

    @Test
    void parameterIdentityAndEffectiveParameters() {
        var query = parameter("a", ParameterLocation.QUERY);
        assertThat(query.sameIdentity(parameter("a", ParameterLocation.HEADER))).isFalse();
        assertThat(query.sameIdentity(parameter("A", ParameterLocation.QUERY))).isFalse();
        assertThat(parameter("a", ParameterLocation.HEADER).sameIdentity(parameter("A", ParameterLocation.HEADER))).isTrue();
        assertThat(parameter("a", ParameterLocation.HEADER).sameIdentity(parameter("b", ParameterLocation.HEADER))).isFalse();
        var shared = List.of(parameter("a", ParameterLocation.QUERY), parameter("b", ParameterLocation.QUERY));
        var item = new PathItem(Optional.empty(), Optional.empty(), Map.of(), List.of(), shared, Map.of());
        var own = parameter("b", ParameterLocation.QUERY);
        var operation = new Operation(List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(own),
            Optional.empty(), Responses.EMPTY, Map.of(), false, Optional.empty(), List.of(), Map.of());
        assertThat(operation.effectiveParameters(item)).containsExactly(shared.getFirst(), own);
        assertThat(query.effectiveSchema()).isEmpty();
    }

    @Test
    void enumsByValue() {
        assertThat(ParameterLocation.of("querystring")).contains(ParameterLocation.QUERYSTRING);
        assertThat(ParameterLocation.of("body")).isEmpty();
        assertThat(ParameterLocation.COOKIE.value()).isEqualTo("cookie");
        assertThat(ParameterStyle.of("deepObject")).contains(ParameterStyle.DEEP_OBJECT);
        assertThat(ParameterStyle.of("nope")).isEmpty();
        assertThat(ParameterStyle.COOKIE.explodeByDefault()).isTrue();
        assertThat(ParameterStyle.SIMPLE.explodeByDefault()).isFalse();
    }

    @Test
    void immutableCopies() {
        var list = Immutable.list(Arrays.asList("a", null));
        assertThat(list).containsExactly("a", null);
        assertThat(Immutable.map(Map.of("k", 1))).containsEntry("k", 1);
    }
}
