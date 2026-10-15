package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Encoding Objects of {@code application/x-www-form-urlencoded} bodies serialize properties like query parameters. */
class FormEncodingTest {

    private static final OpenApiDocument API = new OpenApiLoader(ResourceLoaders.inMemory(Map.of()))
        .parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /forms:
                post:
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            tags: {type: array, items: {type: string}}
                            meta: {type: object, properties: {a: {type: integer}}}
                            pipe: {type: array, items: {type: string}}
                            raw: {type: array, items: {type: integer}}
                            json: {type: object}
                            points: {type: array, items: {type: object, properties: {x: {type: integer}}}}
                        encoding:
                          tags: {explode: false}
                          meta: {style: deepObject}
                          pipe: {x-collection-format: pipes}
                          json: {contentType: application/json}
                  responses: {'204': {description: ok}}
            """).orElseThrow(d -> new AssertionError(d.toString()));

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    @Test
    void parsesAndWritesPropertiesByTheirEncoding() {
        var mediaType = API.operations().getFirst().operation().requestBody().orElseThrow().content().get("application/x-www-form-urlencoded");
        var body = "tags=a,b&meta%5Ba%5D=1&pipe=x|y&raw=1&raw=2&json=%7B%22k%22%3A1%7D";
        var parsed = new BodyParser(new SchemaShapes(API.schemas())).parse(body.getBytes(StandardCharsets.UTF_8),
            ContentType.parse("application/x-www-form-urlencoded").orElseThrow(), mediaType);
        assertThat(parsed.toOptional()).contains(json("{\"tags\":[\"a\",\"b\"],\"pipe\":[\"x\",\"y\"],\"raw\":[1,2],\"json\":{\"k\":1},\"meta\":{\"a\":1}}"));
        var specs = BodyWriter.formSpecs(mediaType.encoding());
        assertThat(specs).containsOnlyKeys("tags", "meta", "pipe");
        assertThat(BodyWriter.form(json("{\"tags\":[\"a\",\"b\"],\"meta\":{\"a\":1},\"pipe\":[\"x\",\"y\"],\"raw\":[1,2]}"), specs))
            .isEqualTo("tags=a,b&meta%5Ba%5D=1&pipe=x%7Cy&raw=1&raw=2");
        var validator = OpenApiValidator.of(API);
        assertThat(validator.validateRequest(HttpRequest.of("POST", "/forms").withBody("application/x-www-form-urlencoded", "meta%5Ba%5D=x"))
            .report().errors()).extracting(issue -> issue.key() + "@" + issue.location()).containsExactly("request.body.schema.type@body/meta/a");
    }

    @Test
    void objectsAreJsonByDefault() {
        var mediaType = API.operations().getFirst().operation().requestBody().orElseThrow().content().get("application/x-www-form-urlencoded");
        var parser = new BodyParser(new SchemaShapes(API.schemas()));
        var form = ContentType.parse("application/x-www-form-urlencoded").orElseThrow();
        // Repeated items, or one JSON array.
        assertThat(parser.parse("points=%7B%22x%22%3A1%7D&points=%7B%22x%22%3A2%7D".getBytes(StandardCharsets.UTF_8), form, mediaType).toOptional())
            .contains(json("{\"points\":[{\"x\":1},{\"x\":2}]}"));
        assertThat(parser.parse("points=%5B%7B%22x%22%3A1%7D%5D".getBytes(StandardCharsets.UTF_8), form, mediaType).toOptional())
            .contains(json("{\"points\":[{\"x\":1}]}"));
        assertThat(parser.parse("points=%7B%22x%22%3A1%7D".getBytes(StandardCharsets.UTF_8), form, mediaType).toOptional())
            .contains(json("{\"points\":[{\"x\":1}]}"));
        assertThat(BodyWriter.form(json("{\"name\":\"Zoë ~ x*y\"}"))).isEqualTo("name=Zo%C3%AB+%7E+x*y");
    }
}
