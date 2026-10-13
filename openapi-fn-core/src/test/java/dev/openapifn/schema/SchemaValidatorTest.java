package dev.openapifn.schema;

import static dev.openapifn.schema.Schemas.json;
import static dev.openapifn.schema.Schemas.messages;
import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.JsonString;
import dev.openapifn.parse.OpenApiDocument;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {

    private static final Dialect D = Dialect.DRAFT_2020_12;

    private static List<String> check(OpenApiDocument api, String schema, String instance, Direction direction) {
        var validator = new SchemaValidator(api.schemas());
        return validator.validate(api.model().components().schemas().get(schema), json(instance), direction).stream()
            .map(SchemaError::toString).toList();
    }

    @Test
    void messagesForEachKeyword() {
        assertThat(messages("{\"type\": \"string\"}", D, "1")).containsExactly("/: must be of type string but was integer");
        assertThat(messages("{\"type\": [\"string\", \"null\"]}", D, "1")).containsExactly("/: must be one of types [string, null] but was integer");
        assertThat(messages("{\"type\": \"number\"}", D, "1.5")).isEmpty();
        assertThat(messages("{\"enum\": [1, \"a\"]}", D, "2")).containsExactly("/: must be one of [1, \"a\"]");
        assertThat(messages("{\"const\": {\"a\": 1}}", D, "{\"a\": 2}")).containsExactly("/: must be {\"a\":1}");
        assertThat(messages("{\"multipleOf\": 0.5}", D, "1.25")).containsExactly("/: must be a multiple of 0.5");
        assertThat(messages("{\"multipleOf\": 0}", D, "1.25")).isEmpty();
        assertThat(messages("{\"maximum\": 3, \"minimum\": 5}", D, "4")).containsExactly("/: must be <= 3", "/: must be >= 5");
        assertThat(messages("{\"exclusiveMaximum\": 3, \"exclusiveMinimum\": 5}", D, "4")).containsExactly("/: must be < 3", "/: must be > 5");
        assertThat(messages("{\"maxLength\": 1, \"minLength\": 3}", D, "\"ab\"")).containsExactly("/: must be at most 1 characters long",
            "/: must be at least 3 characters long");
        assertThat(messages("{\"pattern\": \"^a\"}", D, "\"b\"")).containsExactly("/: must match pattern ^a");
        assertThat(messages("{\"pattern\": \"(\"}", D, "\"b\"")).singleElement().asString().startsWith("/: Invalid regular expression '('");
        assertThat(messages("{\"maxItems\": 1, \"minItems\": 3, \"uniqueItems\": true}", D, "[1, 1]"))
            .containsExactly("/: must have at most 1 items", "/: must have at least 3 items", "/: must not contain duplicate items");
        assertThat(messages("{\"contains\": {\"type\": \"string\"}}", D, "[1]")).containsExactly("/: must contain at least one matching item");
        assertThat(messages("{\"contains\": {\"type\": \"string\"}, \"minContains\": 2, \"maxContains\": 0}", D, "[\"a\"]"))
            .containsExactly("/: must contain at most 0 matching items", "/: must contain at least 2 matching items");
        assertThat(messages("{\"maxProperties\": 0, \"minProperties\": 2, \"required\": [\"b\"]}", D, "{\"a\": 1}"))
            .containsExactly("/: must have at most 0 properties", "/: must have at least 2 properties", "/: missing required property 'b'");
        assertThat(messages("{\"dependentRequired\": {\"a\": [\"b\"]}, \"dependencies\": {\"c\": [\"d\"], \"e\": {\"required\": [\"f\"]}}}", D,
            "{\"a\": 1, \"c\": 1, \"e\": 1}")).containsExactlyInAnyOrder("/: property 'b' is required when 'a' is present",
            "/: property 'd' is required when 'c' is present", "/: missing required property 'f'");
        assertThat(messages("{\"dependentRequired\": {\"a\": \"not an array\"}}", D, "{\"a\": 1}")).isEmpty();
        assertThat(messages("{\"additionalProperties\": false, \"properties\": {\"a\": {}}}", D, "{\"a\": 1, \"b\": 2}"))
            .containsExactly("/b: property 'b' is not allowed");
        assertThat(messages("{\"patternProperties\": {\"(\": {}}, \"additionalProperties\": false}", D, "{\"a\": 1}"))
            .containsExactly("/a: property 'a' is not allowed");
        assertThat(messages("{\"unevaluatedProperties\": false}", D, "{\"a\": 1}")).containsExactly("/a: property 'a' is not allowed");
        assertThat(messages("{\"propertyNames\": {\"maxLength\": 1}}", D, "{\"ab\": 1}"))
            .containsExactly("/ab: property name 'ab' is invalid: must be at most 1 characters long");
        assertThat(messages("{\"not\": {}}", D, "1")).containsExactly("/: must not match the schema in 'not'");
        assertThat(messages("false", D, "1")).containsExactly("/: no value is allowed here");
        assertThat(messages("{\"oneOf\": [{}, {}]}", D, "1")).containsExactly("/: must match exactly one schema in oneOf but matched schemas at [0, 1]");
        assertThat(messages("{\"items\": [{\"type\": \"string\"}], \"additionalItems\": false}", Dialect.DRAFT_7, "[\"a\", 1]"))
            .containsExactly("/1: no value is allowed here");
    }

    @Test
    void combinatorFailuresCarryCauses() {
        var errors = Schemas.validate("{\"anyOf\": [{\"type\": \"string\"}, {\"minimum\": 5}]}", D, "1");
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.keyword()).isEqualTo("anyOf");
            assertThat(error.causes()).hasSize(2);
            assertThat(error.schemaLocation().toString()).isEqualTo("mem:/root.json#/anyOf");
        });
        assertThat(SchemaValidator.describe(errors)).containsExactly("/: must match at least one schema in anyOf",
            "/: must be of type string but was integer", "/: must be >= 5");
        assertThat(messages("{\"oneOf\": [{\"type\": \"string\"}]}", D, "1")).containsExactly("/: must match exactly one schema in oneOf");
    }

    @Test
    void unresolvableReferencesAreReported() {
        assertThat(messages("{\"$ref\": \"#/$defs/missing\"}", D, "1")).containsExactly("/: unresolvable reference '#/$defs/missing'");
        assertThat(messages("{\"$dynamicRef\": \"#missing\"}", D, "1")).containsExactly("/: unresolvable reference '#missing'");
        assertThat(messages("{\"$recursiveRef\": \"#/missing\"}", Dialect.DRAFT_2019_09, "1"))
            .containsExactly("/: unresolvable reference '#/missing'");
        assertThat(messages("{\"$dynamicRef\": \"%zz\"}", D, "1")).containsExactly("/: unresolvable reference '%zz'");
    }

    @Test
    void infiniteRecursionOnTheSameInstanceTerminates() {
        assertThat(messages("{\"$ref\": \"#\"}", D, "1")).isEmpty();
        assertThat(messages("{\"allOf\": [{\"$ref\": \"#\"}], \"type\": \"string\"}", D, "1")).containsExactly("/: must be of type string but was integer");
    }

    @Test
    void dynamicReferenceWithoutAnchorInScopeUsesInitialTarget() {
        var build = Schemas.build("{\"$id\": \"https://example.com/a\", \"$dynamicRef\": \"b#meta\"}", D,
            Map.of("b", "{\"$id\": \"https://example.com/b\", \"$dynamicAnchor\": \"meta\", \"type\": \"string\"}"));
        var registry = build.registry();
        var schema = registry.resolve(java.net.URI.create("mem:/root.json")).orElseThrow();
        assertThat(new SchemaValidator(registry).validate(schema, json("1"))).isNotEmpty();
    }

    @Test
    void recursiveReferenceFindsOutermostRecursiveAnchor() {
        var schema = """
            {"$id": "https://example.com/tree", "$recursiveAnchor": true, "type": "object",
             "properties": {"child": {"$recursiveRef": "#"}, "leaf": {"$ref": "leaf"}},
             "$defs": {"leaf": {"$id": "leaf", "$recursiveAnchor": true, "properties": {"next": {"$recursiveRef": "#"}}}}}""";
        assertThat(messages(schema, Dialect.DRAFT_2019_09, "{\"child\": {\"child\": 1}}")).containsExactly("/child/child: must be of type object but was integer");
        assertThat(messages(schema, Dialect.DRAFT_2019_09, "{\"leaf\": {\"next\": 1}}")).containsExactly("/leaf/next: must be of type object but was integer");
        assertThat(messages("{\"$recursiveRef\": \"#\", \"$defs\": {}}", Dialect.DRAFT_2019_09, "1")).isEmpty();
    }

    @Test
    void contentAssertion() {
        var registry = Schemas.build("""
            {"properties": {
              "b64": {"contentEncoding": "base64", "contentMediaType": "application/json", "contentSchema": {"type": "object"}},
              "json": {"contentMediaType": "application/problem+json"},
              "plain": {"contentEncoding": "base64"},
              "text": {"contentMediaType": "text/plain"}}}""", D, Map.of()).registry();
        var root = Schemas.root(registry);
        var lenient = new SchemaValidator(registry);
        var strict = new SchemaValidator(registry, SchemaValidator.Options.DEFAULT.withAssertContent(true));
        assertThat(lenient.validate(root, json("{\"b64\": \"!!\"}"))).isEmpty();
        assertThat(strict.validate(root, json("{\"b64\": \"!!\"}"))).extracting(SchemaError::message).containsExactly("must be base64 encoded");
        assertThat(strict.validate(root, json("{\"b64\": \"WzFd\"}"))).extracting(SchemaError::message)
            .containsExactly("must be of type object but was array");
        assertThat(strict.validate(root, json("{\"b64\": \"e30=\", \"plain\": \"e30=\", \"text\": \"x\"}"))).isEmpty();
        assertThat(strict.validate(root, json("{\"json\": \"{\"}"))).extracting(SchemaError::message).containsExactly("must contain valid JSON");
        assertThat(strict.validate(root, json("{\"json\": \"[]\"}"))).isEmpty();
        assertThat(strict.options().assertContent()).isTrue();
        assertThat(strict.registry()).isSameAs(registry);
    }

    @Test
    void formatOptions() {
        var registry = Schemas.build("{\"format\": \"even\"}", D, Map.of()).registry();
        var root = Schemas.root(registry);
        FormatValidator even = instance -> instance.asNumber().filter(n -> n.value().intValue() % 2 != 0).map(n -> "must be even");
        var options = SchemaValidator.Options.DEFAULT.withFormat("even", even);
        assertThat(new SchemaValidator(registry, options).validate(root, json("3"))).extracting(SchemaError::message).containsExactly("must be even");
        assertThat(new SchemaValidator(registry, options.withAssertFormats(false)).validate(root, json("3"))).isEmpty();
        assertThat(new SchemaValidator(registry).validate(root, json("3"))).isEmpty();
        assertThat(FormatValidator.forStrings(s -> false, "thing").validate(JsonString.of("x"))).contains("must be a valid thing");
        assertThat(FormatValidator.forStrings(s -> false, "thing").validate(json("1"))).isEqualTo(Optional.empty());
    }

    @Test
    void openApi30NullableAndExclusiveBounds() {
        var api = Schemas.api("""
            openapi: 3.0.3
            info: {title: t, version: v}
            paths: {}
            components:
              schemas:
                Name: {type: string, nullable: true, enum: [a, b]}
                Ref: {allOf: [{$ref: '#/components/schemas/Name'}], nullable: true}
                Strict: {type: string}
                Bounded: {type: number, maximum: 5, exclusiveMaximum: true, minimum: 1, exclusiveMinimum: true}
                Inclusive: {type: number, maximum: 5, minimum: 1}
                Sibling: {$ref: '#/components/schemas/Strict', maxLength: 1}
            """);
        assertThat(check(api, "Name", "null", Direction.NONE)).isEmpty();
        assertThat(check(api, "Name", "\"c\"", Direction.NONE)).containsExactly("/: must be one of [\"a\", \"b\"]");
        assertThat(check(api, "Ref", "null", Direction.NONE)).isEmpty();
        assertThat(check(api, "Strict", "null", Direction.NONE)).containsExactly("/: must be of type string but was null");
        assertThat(check(api, "Bounded", "5", Direction.NONE)).containsExactly("/: must be < 5");
        assertThat(check(api, "Bounded", "1", Direction.NONE)).containsExactly("/: must be > 1");
        assertThat(check(api, "Inclusive", "5", Direction.NONE)).isEmpty();
        assertThat(check(api, "Sibling", "\"abc\"", Direction.NONE)).isEmpty();
        var schema = api.model().components().schemas().get("Name");
        assertThat(schema.types()).containsExactly("string", "null");
        assertThat(schema.nullable()).isTrue();
    }

    @Test
    void readOnlyAndWriteOnlyFollowTheDirection() {
        var api = Schemas.api("""
            openapi: 3.1.0
            info: {title: t, version: v}
            components:
              schemas:
                Id: {type: integer, readOnly: true}
                User:
                  type: object
                  required: [id, password, name]
                  properties:
                    id: {$ref: '#/components/schemas/Id'}
                    password: {type: string, writeOnly: true}
                    name: {type: string}
                Admin:
                  allOf:
                    - $ref: '#/components/schemas/User'
                    - required: [id, level]
                      properties: {level: {type: integer}}
            """);
        assertThat(check(api, "User", "{\"name\": \"n\", \"password\": \"p\"}", Direction.REQUEST)).isEmpty();
        assertThat(check(api, "User", "{\"name\": \"n\", \"password\": \"p\", \"id\": 1}", Direction.REQUEST))
            .containsExactly("/id: is read-only and must not be sent in a request");
        assertThat(check(api, "User", "{\"name\": \"n\", \"id\": 1}", Direction.RESPONSE)).isEmpty();
        assertThat(check(api, "User", "{\"name\": \"n\", \"id\": 1, \"password\": \"p\"}", Direction.RESPONSE))
            .containsExactly("/password: is write-only and must not be returned in a response");
        assertThat(check(api, "User", "{\"name\": \"n\"}", Direction.NONE))
            .containsExactly("/: missing required property 'id'", "/: missing required property 'password'");
        assertThat(check(api, "User", "{\"password\": \"p\"}", Direction.REQUEST)).containsExactly("/: missing required property 'name'");
        assertThat(check(api, "Admin", "{\"name\": \"n\", \"password\": \"p\", \"level\": 1}", Direction.REQUEST)).isEmpty();
        assertThat(check(api, "Admin", "{\"name\": \"n\", \"password\": \"p\"}", Direction.REQUEST))
            .containsExactly("/: missing required property 'level'");
    }

    @Test
    void discriminators() {
        var api = Schemas.api("""
            openapi: 3.1.0
            info: {title: t, version: v}
            components:
              schemas:
                Pet:
                  oneOf: [{$ref: '#/components/schemas/Cat'}, {$ref: '#/components/schemas/Dog'}]
                  discriminator: {propertyName: kind, mapping: {kitty: '#/components/schemas/Cat'}}
                AnyPet:
                  anyOf: [{$ref: '#/components/schemas/Cat'}, {$ref: '#/components/schemas/Dog'}]
                  discriminator: {propertyName: kind, defaultMapping: Dog}
                Cat: {type: object, required: [kind, meows], properties: {kind: {type: string}, meows: {type: boolean}}}
                Dog: {type: object, required: [kind, barks], properties: {kind: {type: string}, barks: {type: boolean}}}
                Base:
                  type: object
                  required: [kind]
                  properties: {kind: {type: string}}
                  discriminator: {propertyName: kind}
                Lion:
                  allOf: [{$ref: '#/components/schemas/Base'}, {required: [roars], properties: {roars: {type: boolean}}}]
                Wide: {type: object}
                Narrow: {type: object, properties: {kind: {type: string}}}
                Strict: {type: object, required: [never]}
                Overlap:
                  oneOf: [{$ref: '#/components/schemas/Wide'}, {$ref: '#/components/schemas/Narrow'}, {$ref: '#/components/schemas/Strict'}]
                  discriminator: {propertyName: kind}
                OverlapNoDiscriminator:
                  oneOf: [{$ref: '#/components/schemas/Wide'}, {$ref: '#/components/schemas/Narrow'}]
            """);
        assertThat(check(api, "Pet", "{\"kind\": \"kitty\", \"meows\": true}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Pet", "{\"kind\": \"kitty\"}", Direction.NONE)).containsExactly("/: missing required property 'meows'");
        assertThat(check(api, "Pet", "{\"kind\": \"Dog\"}", Direction.NONE)).containsExactly("/: missing required property 'barks'");
        assertThat(check(api, "Pet", "{\"meows\": true}", Direction.NONE)).containsExactly("/: missing discriminator property 'kind'");
        assertThat(check(api, "Pet", "{\"kind\": \"fish\"}", Direction.NONE))
            .containsExactly("/kind: unknown discriminator value 'fish' for property 'kind'");
        assertThat(check(api, "Pet", "1", Direction.NONE)).containsExactly("/: must match exactly one schema in oneOf");
        assertThat(check(api, "AnyPet", "{\"kind\": \"fish\"}", Direction.NONE)).containsExactly("/: missing required property 'barks'");
        assertThat(check(api, "AnyPet", "{\"kind\": \"Cat\"}", Direction.NONE)).containsExactly("/: missing required property 'meows'");
        assertThat(check(api, "Base", "{\"kind\": \"Lion\"}", Direction.NONE)).containsExactly("/: missing required property 'roars'");
        assertThat(check(api, "Base", "{\"kind\": \"Base\"}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Base", "{\"kind\": \"Unknown\"}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Base", "[]", Direction.NONE)).containsExactly("/: must be of type object but was array");
        assertThat(check(api, "Lion", "{\"kind\": \"Lion\", \"roars\": true}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Overlap", "{\"kind\": \"Wide\"}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Overlap", "{\"kind\": \"Narrow\"}", Direction.NONE)).isEmpty();
        assertThat(check(api, "Overlap", "{\"kind\": \"other\"}", Direction.NONE)).containsExactly("/: must match exactly one schema in oneOf but matched schemas at [0, 1]");
        assertThat(check(api, "Overlap", "{\"kind\": \"Strict\"}", Direction.NONE)).containsExactly("/: must match exactly one schema in oneOf but matched schemas at [0, 1]");
        assertThat(check(api, "Overlap", "{}", Direction.NONE)).containsExactly("/: must match exactly one schema in oneOf but matched schemas at [0, 1]");
        assertThat(check(api, "OverlapNoDiscriminator", "{\"kind\": \"Wide\"}", Direction.NONE))
            .containsExactly("/: must match exactly one schema in oneOf but matched schemas at [0, 1]");
    }

    @Test
    void restrictedPropertiesThroughConditionalAndRecursiveApplicators() {
        var api = Schemas.api("""
            openapi: 3.1.0
            info: {title: t, version: v}
            components:
              schemas:
                Node:
                  type: object
                  required: [id, created, etag]
                  allOf: [{$ref: '#/components/schemas/Node'}]
                  if: {properties: {id: {readOnly: true}}}
                  then: {properties: {created: {readOnly: true}}}
                  dependentSchemas: {x: {properties: {etag: {readOnly: true}}}}
            """);
        assertThat(check(api, "Node", "{}", Direction.REQUEST)).isEmpty();
        assertThat(check(api, "Node", "{}", Direction.RESPONSE)).hasSize(3);
        assertThat(messages("{\"$dynamicRef\": \"#/$defs/s\", \"$defs\": {\"s\": {\"type\": \"string\"}}}", D, "1"))
            .containsExactly("/: must be of type string but was integer");
        assertThat(messages("{\"$dynamicRef\": \"s.json\", \"$defs\": {\"s\": {\"$id\": \"s.json\", \"type\": \"string\"}}}", D, "1"))
            .containsExactly("/: must be of type string but was integer");
    }
}
