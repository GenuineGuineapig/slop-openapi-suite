package dev.openapifn.schema;

import static dev.openapifn.schema.Schemas.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaViewTest {

    private static final URI DOC = URI.create("mem:/doc.json");

    private static Schema schema(String text, Dialect dialect) {
        return Schema.at(json(text), DOC, JsonPointer.ROOT, DOC, dialect);
    }

    @Test
    void accessors() {
        var schema = schema("""
            {"$id": "https://example.com/s", "$ref": "#/a", "$dynamicRef": "#d", "type": ["string", "null", 1], "required": ["a", 1],
             "format": "date", "title": "T", "description": "D", "pattern": "^a", "enum": [1], "const": 2, "default": 3,
             "examples": [4], "example": 5, "readOnly": true, "writeOnly": true, "deprecated": true, "maximum": 9,
             "properties": {"p": {"$id": "p"}, "bad": 1}, "allOf": [{}, 1, true], "not": false, "items": [{}],
             "xml": {"name": "n", "namespace": "ns", "prefix": "x", "attribute": true, "wrapped": true, "x-e": 1},
             "discriminator": {"propertyName": "k", "mapping": {"a": "#/A", "b": 1}, "defaultMapping": "#/D", "x-d": 1},
             "x-ext": true}""", Dialect.DRAFT_2020_12);
        assertThat(schema.baseUri()).isEqualTo(URI.create("https://example.com/s"));
        assertThat(schema.location()).isEqualTo(URI.create("mem:/doc.json#"));
        assertThat(schema.ref()).contains("#/a");
        assertThat(schema.dynamicRef()).contains("#d");
        assertThat(schema.types()).containsExactly("string", "null");
        assertThat(schema.nullable()).isTrue();
        assertThat(schema.required()).containsExactly("a");
        assertThat(schema.format()).contains("date");
        assertThat(schema.title()).contains("T");
        assertThat(schema.description()).contains("D");
        assertThat(schema.pattern()).contains("^a");
        assertThat(schema.enumValues()).containsExactly(json("1"));
        assertThat(schema.constValue()).contains(json("2"));
        assertThat(schema.defaultValue()).contains(json("3"));
        assertThat(schema.examples()).containsExactly(json("4"), json("5"));
        assertThat(schema.readOnly()).isTrue();
        assertThat(schema.writeOnly()).isTrue();
        assertThat(schema.deprecated()).isTrue();
        assertThat(schema.number("maximum")).contains(BigDecimal.valueOf(9));
        assertThat(schema.subschemaMap("properties")).containsOnlyKeys("p");
        assertThat(schema.subschemaMap("properties").get("p").baseUri()).isEqualTo(URI.create("https://example.com/p"));
        assertThat(schema.subschemaList("allOf")).hasSize(2);
        assertThat(schema.subschema("not").orElseThrow().rejectsEverything()).isTrue();
        assertThat(schema.subschema("items")).isEmpty();
        assertThat(schema.subschemaList("items")).hasSize(1);
        assertThat(schema.subschema("missing")).isEmpty();
        assertThat(schema.subschemaMap("missing")).isEmpty();
        var xml = schema.xml().orElseThrow();
        assertThat(xml.effectiveNodeType()).isEqualTo("attribute");
        assertThat(xml.extensions()).containsKey("x-e");
        var discriminator = schema.discriminator().orElseThrow();
        assertThat(discriminator.mapping()).containsExactly(Map.entry("a", "#/A"));
        assertThat(discriminator.defaultMapping()).contains("#/D");
        assertThat(discriminator.extensions()).containsKey("x-d");
        assertThat(schema.extensions()).containsOnlyKeys("x-ext");
        assertThat(schema.has("format")).isTrue();
        assertThat(schema.name()).isEmpty();
        assertThat(schema).hasToString("mem:/doc.json#");
    }

    @Test
    void defaultsAndBooleanSchemas() {
        var empty = schema("{}", Dialect.DRAFT_2020_12);
        assertThat(empty.acceptsEverything()).isTrue();
        assertThat(empty.isBooleanSchema()).isFalse();
        assertThat(empty.types()).isEmpty();
        assertThat(empty.nullable()).isFalse();
        assertThat(empty.readOnly()).isFalse();
        assertThat(empty.xml()).isEmpty();
        assertThat(empty.discriminator()).isEmpty();
        assertThat(schema("{\"discriminator\": {}}", Dialect.DRAFT_2020_12).discriminator()).isEmpty();
        assertThat(schema("{\"discriminator\": {\"propertyName\": \"k\"}}", Dialect.DRAFT_2020_12).discriminator().orElseThrow().mapping()).isEmpty();
        var xml = schema("{\"xml\": {\"nodeType\": \"text\"}}", Dialect.DRAFT_2020_12).xml().orElseThrow();
        assertThat(xml.effectiveNodeType()).isEqualTo("text");
        assertThat(schema("{\"xml\": {}}", Dialect.DRAFT_2020_12).xml().orElseThrow().effectiveNodeType()).isEqualTo("element");
        var accept = schema("true", Dialect.DRAFT_2020_12);
        assertThat(accept.isBooleanSchema()).isTrue();
        assertThat(accept.acceptsEverything()).isTrue();
        assertThat(accept.object()).isEqualTo(JsonObject.EMPTY);
        assertThat(schema("false", Dialect.DRAFT_2020_12).acceptsEverything()).isFalse();
        assertThat(schema("{\"type\": \"string\"}", Dialect.DRAFT_2020_12).acceptsEverything()).isFalse();
        assertThat(schema("{\"type\": 5}", Dialect.DRAFT_2020_12).types()).isEmpty();
        assertThat(Schema.at(JsonBoolean.TRUE, DOC, JsonPointer.of("a"), DOC, Dialect.DRAFT_7).name()).isEqualTo("a");
        assertThatThrownBy(() -> new Schema(null, DOC, JsonPointer.ROOT, DOC, Dialect.DRAFT_7)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Schema(JsonBoolean.TRUE, null, JsonPointer.ROOT, DOC, Dialect.DRAFT_7)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Schema(JsonBoolean.TRUE, DOC, null, DOC, Dialect.DRAFT_7)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Schema(JsonBoolean.TRUE, DOC, JsonPointer.ROOT, null, Dialect.DRAFT_7)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Schema(JsonBoolean.TRUE, DOC, JsonPointer.ROOT, DOC, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullableAndIdentifiersPerDialect() {
        assertThat(schema("{\"type\": \"string\", \"nullable\": true}", Dialect.OAS_3_0).types()).containsExactly("string", "null");
        assertThat(schema("{\"nullable\": true}", Dialect.OAS_3_0).nullable()).isTrue();
        assertThat(schema("{\"type\": \"string\", \"nullable\": false}", Dialect.OAS_3_0).nullable()).isFalse();
        assertThat(schema("{\"type\": \"string\", \"nullable\": true}", Dialect.DRAFT_2020_12).nullable()).isFalse();
        assertThat(schema("{\"$id\": \"https://x/y\"}", Dialect.OAS_3_0).baseUri()).isEqualTo(DOC);
        assertThat(schema("{\"id\": \"https://x/y\"}", Dialect.DRAFT_4).baseUri()).isEqualTo(URI.create("https://x/y"));
        assertThat(schema("{\"$id\": \"https://x/y\", \"$ref\": \"#\"}", Dialect.DRAFT_7).baseUri()).isEqualTo(DOC);
        assertThat(schema("{\"$id\": \"%zz\"}", Dialect.DRAFT_7).baseUri()).isEqualTo(DOC);
        assertThat(schema("{\"$schema\": \"http://json-schema.org/draft-04/schema#\"}", Dialect.DRAFT_2020_12).dialect()).isEqualTo(Dialect.DRAFT_4);
    }

    @Test
    void dialects() {
        assertThat(Dialect.fromUri("https://spec.openapis.org/oas/3.0/dialect")).contains(Dialect.OAS_3_0);
        assertThat(Dialect.fromUri("https://spec.openapis.org/oas/3.1/dialect/base")).contains(Dialect.DRAFT_2020_12);
        assertThat(Dialect.fromUri("http://json-schema.org/draft-06/schema#")).contains(Dialect.DRAFT_6);
        assertThat(Dialect.fromUri("https://json-schema.org/draft/2019-09/schema")).contains(Dialect.DRAFT_2019_09);
        assertThat(Dialect.fromUri("https://example.com/custom")).isEmpty();
        assertThat(Dialect.DRAFT_6.idKeyword()).contains("$id");
        assertThat(Dialect.OAS_3_0.idKeyword()).isEmpty();
        assertThat(Dialect.DRAFT_2020_12.arrayFormItems()).isFalse();
        assertThat(Dialect.DRAFT_4.booleanExclusiveBounds()).isTrue();
    }

    @Test
    void registry() {
        var build = Schemas.build("""
            {"$defs": {
               "a": {"$ref": "#/$defs/b"}, "b": {"$ref": "#/$defs/c"}, "c": {"type": "string"},
               "loop": {"$ref": "#/$defs/loop2"}, "loop2": {"$ref": "#/$defs/loop"},
               "dangling": {"$ref": "#/$defs/nothing"},
               "named": {"$id": "https://example.com/named", "$anchor": "here", "$dynamicAnchor": "dyn"},
               "plainId": {"$id": "#plain", "type": "integer"},
               "invalid": {"$ref": "%zz"},
               "remote": {"$ref": "other.json#/x"},
               "missingDoc": {"$ref": "missing.json"},
               "notSchema": {"$ref": "#/$defs/c/type"},
               "draft4": {"$schema": "http://json-schema.org/draft-04/schema#", "properties": {"p": {"id": "#frag"}}},
               "pointerId": {"$id": "https://example.com/z#/p"},
               "badId": {"$id": "%zz"},
               "missingInResource": {"$ref": "https://example.com/named#/nope"}
            }}""", Dialect.DRAFT_2020_12, Map.of("other.json", "{\"x\": {\"type\": \"integer\"}}"));
        var registry = build.registry();
        assertThat(build.diagnostics()).extracting(d -> d.message()).containsExactlyInAnyOrder(
            "Cannot resolve '#/$defs/nothing': target not found",
            "Invalid URI reference '%zz': Malformed escape pair",
            "Cannot resolve 'missing.json': No in-memory document mem:/missing.json",
            "Cannot resolve '#/$defs/c/type': target not found",
            "Cannot resolve 'https://example.com/named#/nope': target not found");
        var root = Schemas.root(registry);
        var defs = root.subschemaMap("$defs");
        assertThat(registry.deref(defs.get("a")).pointer()).isEqualTo(JsonPointer.of("$defs", "c"));
        assertThat(registry.deref(defs.get("loop")).ref()).isPresent();
        assertThat(registry.deref(defs.get("dangling"))).isEqualTo(defs.get("dangling"));
        assertThat(registry.dereference(defs.get("c"))).isEmpty();
        assertThat(registry.resolve(URI.create("https://example.com/named#here"))).isPresent();
        assertThat(registry.resolve(URI.create("https://example.com/named#nope"))).isEmpty();
        assertThat(registry.dynamicAnchor(URI.create("https://example.com/named"), "dyn")).isPresent();
        assertThat(registry.dynamicAnchor(URI.create("https://example.com/other"), "dyn")).isEmpty();
        assertThat(registry.resolve(URI.create("mem:/root.json#plain"))).isPresent();
        assertThat(registry.resolve(URI.create("mem:/root.json#/$defs/c/type"))).isEmpty();
        assertThat(registry.resolve(URI.create("mem:/root.json#/~"))).isEmpty();
        assertThat(registry.resolve(root, "other.json#/x")).isPresent();
        assertThat(registry.resolve(root, "%zz")).isEmpty();
        assertThat(registry.resolveMapping(root, "#/$defs/c")).isPresent();
        assertThat(registry.resolveMapping(root, "c")).isEmpty();
        assertThat(registry.documents()).containsKeys(Schemas.ROOT, URI.create("mem:/other.json"));
        assertThat(registry.resolve(URI.create("mem:/root.json#frag"))).isPresent();
    }

    @Test
    void emptyRegistry() {
        var empty = SchemaRegistry.empty();
        assertThat(empty.documents()).isEmpty();
        assertThat(empty.resolve(URI.create("mem:/x#/a"))).isEmpty();
    }

    @Test
    void registryWithOpenApiDocuments() {
        var api = Schemas.api("""
            openapi: 3.1.0
            info: {title: t, version: v}
            components:
              schemas:
                Pet: {discriminator: {propertyName: k, mapping: {cat: Cat, dog: '#/components/schemas/Cat', fish: Fish}}, oneOf: [{$ref: '#/components/schemas/Cat'}]}
                Cat: {type: object}
                Fish: {$id: 'https://example.com/fish', type: object}
            """);
        var registry = api.schemas();
        var pet = api.model().components().schemas().get("Pet");
        assertThat(registry.resolveMapping(pet, "Cat")).isPresent();
        assertThat(registry.resolveMapping(pet, "Fish").orElseThrow().baseUri()).isEqualTo(URI.create("https://example.com/fish"));
        assertThat(api.warnings()).isEmpty();
        var invalid = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            components:
              schemas:
                Pet: {discriminator: {propertyName: k, mapping: {x: Nope}}}
            """);
        assertThat(invalid.failure().orElseThrow()).extracting(d -> d.message()).containsExactly("Cannot resolve 'Nope': No in-memory document mem:/Nope");
        assertThat(Optional.of(registry.documents().size())).contains(1);
    }
}
