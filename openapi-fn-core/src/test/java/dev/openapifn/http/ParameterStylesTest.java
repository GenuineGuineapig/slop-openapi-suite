package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The style/explode serialization table of the OpenAPI specification, checked in both
 * directions: {@link ParameterSerializer} produces the serialized form and
 * {@link ParameterParser} reads it back.
 */
class ParameterStylesTest {

    private static final String PRIMITIVE = "\"blue\"";
    private static final String ARRAY = "[\"blue\",\"black\",\"brown\"]";
    private static final String OBJECT = "{\"R\":100,\"G\":200,\"B\":150}";

    static Stream<Arguments> table() {
        return Stream.of(
            // in, style, explode, value, serialized (path value, query string, header value or cookie header)
            Arguments.of("path", "simple", false, PRIMITIVE, "blue"),
            Arguments.of("path", "simple", false, ARRAY, "blue,black,brown"),
            Arguments.of("path", "simple", false, OBJECT, "R,100,G,200,B,150"),
            Arguments.of("path", "simple", true, ARRAY, "blue,black,brown"),
            Arguments.of("path", "simple", true, OBJECT, "R=100,G=200,B=150"),
            Arguments.of("path", "label", false, PRIMITIVE, ".blue"),
            Arguments.of("path", "label", false, ARRAY, ".blue,black,brown"),
            Arguments.of("path", "label", false, OBJECT, ".R,100,G,200,B,150"),
            Arguments.of("path", "label", true, ARRAY, ".blue.black.brown"),
            Arguments.of("path", "label", true, OBJECT, ".R=100.G=200.B=150"),
            Arguments.of("path", "matrix", false, PRIMITIVE, ";color=blue"),
            Arguments.of("path", "matrix", false, ARRAY, ";color=blue,black,brown"),
            Arguments.of("path", "matrix", false, OBJECT, ";color=R,100,G,200,B,150"),
            Arguments.of("path", "matrix", true, ARRAY, ";color=blue;color=black;color=brown"),
            Arguments.of("path", "matrix", true, OBJECT, ";R=100;G=200;B=150"),
            Arguments.of("query", "form", true, PRIMITIVE, "color=blue"),
            Arguments.of("query", "form", false, ARRAY, "color=blue,black,brown"),
            Arguments.of("query", "form", false, OBJECT, "color=R,100,G,200,B,150"),
            Arguments.of("query", "form", true, ARRAY, "color=blue&color=black&color=brown"),
            Arguments.of("query", "form", true, OBJECT, "R=100&G=200&B=150"),
            Arguments.of("query", "spaceDelimited", false, ARRAY, "color=blue%20black%20brown"),
            Arguments.of("query", "spaceDelimited", false, OBJECT, "color=R%20100%20G%20200%20B%20150"),
            Arguments.of("query", "pipeDelimited", false, ARRAY, "color=blue%7Cblack%7Cbrown"),
            Arguments.of("query", "pipeDelimited", false, OBJECT, "color=R%7C100%7CG%7C200%7CB%7C150"),
            Arguments.of("query", "pipeDelimited", true, ARRAY, "color=blue&color=black&color=brown"),
            Arguments.of("query", "deepObject", true, OBJECT, "color%5BR%5D=100&color%5BG%5D=200&color%5BB%5D=150"),
            Arguments.of("header", "simple", false, PRIMITIVE, "blue"),
            Arguments.of("header", "simple", false, ARRAY, "blue,black,brown"),
            Arguments.of("header", "simple", false, OBJECT, "R,100,G,200,B,150"),
            Arguments.of("header", "simple", true, OBJECT, "R=100,G=200,B=150"),
            Arguments.of("cookie", "form", true, PRIMITIVE, "color=blue"),
            Arguments.of("cookie", "form", false, ARRAY, "color=blue,black,brown"),
            Arguments.of("cookie", "form", false, OBJECT, "color=R,100,G,200,B,150"),
            Arguments.of("cookie", "form", true, ARRAY, "color=blue; color=black; color=brown"),
            Arguments.of("cookie", "form", true, OBJECT, "R=100; G=200; B=150"),
            Arguments.of("cookie", "cookie", true, ARRAY, "color=blue; color=black; color=brown"),
            Arguments.of("cookie", "cookie", false, ARRAY, "color=blue,black,brown"));
    }

    private static OpenApiDocument api(String in, String style, boolean explode, JsonValue value) {
        var type = switch (value) {
            case dev.openapifn.json.JsonArray ignored -> "{type: array, items: {type: string}}";
            case dev.openapifn.json.JsonObject ignored -> "{type: object, properties: {R: {type: integer}, G: {type: integer}, B: {type: integer}}}";
            default -> "{type: string}";
        };
        var yaml = """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              %s:
                get:
                  parameters:
                    - {name: color, in: %s, required: true, style: %s, explode: %s, schema: %s}
                  responses: {'200': {description: ok}}
            """.formatted(in.equals("path") ? "/p/{color}" : "/p", in, style, explode, type);
        return api(yaml);
    }

    static OpenApiDocument api(String yaml) {
        return new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), yaml)
            .orElseThrow(d -> new AssertionError(d.toString()));
    }

    @ParameterizedTest(name = "{0} {1} explode={2} {3}")
    @MethodSource("table")
    void serializesAndParses(String in, String style, boolean explode, String json, String serialized) {
        var value = Json.parse(json).orElseThrow(e -> new AssertionError(e.toString()));
        var api = api(in, style, explode, value);
        var parameter = api.operations().getFirst().parameters().getFirst();
        var spec = new ParameterSerializer.Spec(parameter.name(), parameter.in(), parameter.style(), parameter.explode(), false);
        var written = switch (parameter.in()) {
            case PATH -> ParameterSerializer.path(spec, value);
            case QUERY -> ParameterSerializer.queryString(ParameterSerializer.query(spec, value));
            case HEADER -> ParameterSerializer.header(spec, value);
            default -> ParameterSerializer.cookieHeader(ParameterSerializer.cookie(spec, value));
        };
        assertThat(written).isEqualTo(serialized);
        var request = switch (parameter.in()) {
            case PATH -> HttpRequest.of("GET", "/p/" + serialized);
            case QUERY -> HttpRequest.of("GET", "/p?" + serialized);
            case HEADER -> HttpRequest.of("GET", "/p").withHeader("color", serialized);
            default -> HttpRequest.of("GET", "/p").withHeader("Cookie", serialized);
        };
        var sources = ParameterParser.Sources.of(request, parameter.in() == ParameterLocation.PATH ? Map.of("color", serialized) : Map.of());
        var parsed = new ParameterParser(new SchemaShapes(api.schemas())).parse(parameter, sources, Set.of());
        assertThat(parsed.toOptional().orElseThrow()).contains(value);
    }
}
