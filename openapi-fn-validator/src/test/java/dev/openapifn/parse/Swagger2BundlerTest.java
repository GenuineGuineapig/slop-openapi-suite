package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Swagger2BundlerTest {

    private static final OpenApiDocument API = OpenApiLoader.defaults().load("classpath:swagger2/multi/api.yaml")
        .orElseThrow(diagnostics -> new AssertionError(diagnostics.toString()));

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    private static String schemaJson(String name) {
        return API.model().components().schemas().get(name).object().toJson();
    }

    @Test
    void bundlesEveryKindOfReference() {
        assertThat(API.model().components().schemas()).containsOnlyKeys("Owner", "pet", "Kind", "Owner2", "Error");
        assertThat(API.operations()).extracting(operation -> operation.displayName()).containsExactly("listPets", "getPet", "createOwner");
        assertThat(API.model().paths().items().get("/pets").extensions()).containsKey("x-note");

        var limit = API.operation("listPets").orElseThrow().parameters().getFirst();
        assertThat(limit.name()).isEqualTo("limit");
        assertThat(limit.schema().orElseThrow().object().toJson()).isEqualTo("{\"type\":\"integer\",\"maximum\":100}");
        var getPet = API.operation("getPet").orElseThrow();
        assertThat(getPet.parameters()).extracting(p -> p.name() + ":" + p.in()).containsExactly("id:PATH", "X-Trace:HEADER");
        assertThat(getPet.operation().responses().forStatus(404).orElseThrow().description()).contains("Not found");
        assertThat(getPet.operation().responses().forStatus(404).orElseThrow().content().get("application/json").schema().orElseThrow().ref())
            .contains("#/components/schemas/Error");
        assertThat(getPet.operation().responses().defaultResponse().orElseThrow().description()).contains("Problem");

        assertThat(schemaJson("pet")).isEqualTo("{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"tag\":{\"type\":\"string\",\"nullable\":true},\"owner\":{\"$ref\":\"#/components/schemas/Owner\"},"
            + "\"children\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/components/schemas/pet\"}},\"kind\":{\"$ref\":\"#/components/schemas/Kind\"}}}");
        // The schema document's own definitions are copied like any other schema, not kept inside the copy.
        assertThat(schemaJson("Kind")).isEqualTo("{\"type\":\"string\",\"enum\":[\"cat\",\"dog\"]}");
        assertThat(API.schemas().deref(API.model().components().schemas().get("Owner2")).discriminator().orElseThrow().propertyName()).isEqualTo("kind");
        var create = API.operation("createOwner").orElseThrow();
        assertThat(create.operation().requestBody().orElseThrow().content().get("application/json").schema().orElseThrow().ref())
            .contains("#/components/schemas/Owner2");
        assertThat(create.parameters()).extracting(p -> p.name()).containsExactly("X-Trace");
    }

    @Test
    void validatesAgainstTheBundledDescription() {
        var validator = OpenApiValidator.of(API);
        var request = HttpRequest.of("GET", "/v1/pets/1");
        assertThat(validator.validateResponse(request, HttpResponse.of(200).withBody("application/json",
            "{\"name\":\"Rex\",\"tag\":null,\"kind\":\"dog\",\"children\":[{\"name\":\"Pup\"}]}")).isEmpty()).isTrue();
        assertThat(validator.validateResponse(request, HttpResponse.of(200).withBody("application/json", "{\"name\":\"Rex\",\"kind\":\"fish\"}"))
            .errors()).extracting(issue -> issue.key()).containsExactly("response.body.schema.enum");
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/v1/pets?limit=500")).report().errors()).extracting(issue -> issue.key())
            .containsExactly("request.parameter.query.schema.maximum");
    }

    @Test
    void keepsWhatCannotBeResolved() {
        var root = URI.create("mem:/api.yaml");
        Map<URI, JsonValue> documents = Map.of(
            URI.create("mem:/loop.yaml"), json("{\"p\":{\"$ref\":\"#/p\"},\"r\":{\"$ref\":\"#/r\"},\"i\":{\"$ref\":\"#/i\"}}"),
            URI.create("mem:/plain"), json("{\"type\":\"string\"}"));
        var bundled = Swagger2Bundler.bundle((JsonObject) json("""
            {"swagger": "2.0",
             "paths": {
               "/missing": {"$ref": "missing.yaml#/item"},
               "/loop": {"$ref": "loop.yaml#/i"},
               "/a": {"get": {
                 "parameters": [{"$ref": "missing.yaml#/p"}, {"$ref": "loop.yaml#/p"}, {"name": "b", "in": "body", "schema": {"$ref": 1}}],
                 "responses": {"200": {"$ref": "missing.yaml#/r"}, "201": {"$ref": "loop.yaml#/r"},
                   "202": {"description": "d", "schema": {"type": "object", "properties": {
                     "plain": {"$ref": "plain"}, "anchor": {"$ref": "plain#anchor"}, "invalid": {"$ref": "%zz"}}}}}}}}}
            """), root, uri -> Optional.ofNullable(documents.get(uri)));
        assertThat(bundled.toJson()).isEqualTo(json("""
            {"swagger": "2.0",
             "paths": {
               "/missing": {"$ref": "missing.yaml#/item"},
               "/loop": {},
               "/a": {"get": {
                 "parameters": [{"$ref": "missing.yaml#/p"}, {}, {"name": "b", "in": "body", "schema": {"$ref": 1}}],
                 "responses": {"200": {"$ref": "missing.yaml#/r"}, "201": {},
                   "202": {"description": "d", "schema": {"type": "object", "properties": {
                     "plain": {"$ref": "#/definitions/plain"}, "anchor": {"$ref": "plain#anchor"}, "invalid": {"$ref": "%zz"}}}}}}}},
             "definitions": {"plain": {"type": "string"}}}
            """).toJson());
        var loaded = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(root, """
            swagger: '2.0'
            info: {title: t, version: v}
            paths:
              /a: {get: {parameters: [{$ref: 'missing.yaml#/p'}], responses: {'200': {description: ok}}}}
              /b: {$ref: 'missing-item.yaml'}
            """);
        assertThat(loaded.failure().orElseThrow()).extracting(Diagnostic::message)
            .anyMatch(message -> message.startsWith("Cannot resolve reference 'missing.yaml#/p'"))
            .anyMatch(message -> message.startsWith("Cannot resolve reference 'missing-item.yaml'"));
    }
}
