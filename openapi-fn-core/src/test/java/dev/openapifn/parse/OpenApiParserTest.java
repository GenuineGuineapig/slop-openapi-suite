package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonString;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.model.SpecVersion;
import dev.openapifn.schema.Dialect;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenApiParserTest {

    private static final URI ROOT = URI.create("mem:/api.yaml");

    static Result<OpenApiDocument, List<Diagnostic>> parse(String yaml, Map<String, String> others) {
        var documents = new HashMap<URI, String>();
        others.forEach((name, content) -> documents.put(URI.create("mem:/" + name), content));
        return new OpenApiLoader(ResourceLoaders.inMemory(documents)).parse(ROOT, yaml);
    }

    static OpenApiDocument ok(String yaml) {
        return parse(yaml, Map.of()).orElseThrow(d -> new AssertionError(d.toString()));
    }

    static List<String> messages(String yaml, Map<String, String> others) {
        return parse(yaml, others).fold(OpenApiDocument::warnings, d -> d).stream().map(Diagnostic::message).toList();
    }

    static List<String> messages(String yaml) {
        return messages(yaml, Map.of());
    }

    static String v31(String body) {
        return "openapi: 3.1.0\ninfo: {title: t, version: v}\n" + body;
    }

    static String v30(String body) {
        return "openapi: 3.0.3\ninfo: {title: t, version: v}\n" + body;
    }

    @Test
    void rootValidation() {
        assertThat(messages("info: {}")).contains("Missing required field 'openapi'", "Not an OpenAPI document");
        assertThat(messages("openapi: 2.0")).containsExactlyInAnyOrder("Field 'openapi' must be a string", "Not an OpenAPI document");
        assertThat(messages("openapi: '2.0'")).containsExactlyInAnyOrder("Unsupported OpenAPI version '2.0'");
        assertThat(messages("openapi: 3.1.0\npaths: {}")).containsExactlyInAnyOrder("Missing required field 'info'");
        assertThat(messages("openapi: 3.0.0\ninfo: {title: t, version: v}")).containsExactlyInAnyOrder("Missing required field 'paths'");
        assertThat(messages(v31(""))).containsExactlyInAnyOrder("At least one of 'paths', 'components' or 'webhooks' is required");
        assertThat(messages(v31("paths: {}\nfoo: 1"))).containsExactlyInAnyOrder("Unknown field 'foo' in OpenAPI Object");
        assertThat(messages("[]")).contains("OpenAPI Object must be an object");
    }

    @Test
    void versionsAndDialects() {
        var v30 = ok(v30("paths: {}\ncomponents: {schemas: {A: {type: string}}}"));
        assertThat(v30.model().version()).isEqualTo(SpecVersion.V3_0);
        assertThat(v30.model().components().schemas().get("A").dialect()).isEqualTo(Dialect.OAS_3_0);
        var draft7 = ok(v31("jsonSchemaDialect: http://json-schema.org/draft-07/schema#\ncomponents: {schemas: {A: {type: string}}}"));
        assertThat(draft7.model().components().schemas().get("A").dialect()).isEqualTo(Dialect.DRAFT_7);
        assertThat(messages(v31("jsonSchemaDialect: https://example.com/custom\npaths: {}")))
            .containsExactlyInAnyOrder("Unknown jsonSchemaDialect 'https://example.com/custom'; using JSON Schema 2020-12");
        var v32 = ok("openapi: 3.2.0\n$self: https://example.com/api.yaml\ninfo: {title: t, version: v}\npaths: {}");
        assertThat(v32.model().version()).isEqualTo(SpecVersion.V3_2);
        assertThat(v32.model().self()).contains("https://example.com/api.yaml");
    }

    @Test
    void loadsMultiFilePetstore() {
        var doc = OpenApiLoader.defaults().load("classpath:petstore/openapi.yaml").orElseThrow(d -> new AssertionError(d.toString()));
        var model = doc.model();
        assertThat(model.info().license().orElseThrow().identifier()).contains("Apache-2.0");
        assertThat(model.servers().getFirst().defaultUrl()).isEqualTo("https://prod.example.com/api/v1");
        assertThat(doc.operations()).extracting(op -> op.method() + " " + op.path())
            .containsExactly("GET /pets", "POST /pets", "GET /pets/{petId}", "DELETE /pets/{petId}");
        var getPet = doc.operation("getPet").orElseThrow();
        assertThat(getPet.parameters()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("petId");
            assertThat(p.in()).isEqualTo(ParameterLocation.PATH);
            assertThat(p.style()).isEqualTo(ParameterStyle.SIMPLE);
        });
        assertThat(getPet.security()).singleElement().satisfies(r -> assertThat(r.schemes()).containsKey("apiKey"));
        assertThat(doc.operation("deletePet").orElseThrow().security()).isEmpty();
        var notFound = getPet.operation().responses().forStatus(404).orElseThrow();
        assertThat(notFound.description()).contains("No such pet");
        assertThat(doc.operation("listPets").orElseThrow().operation().responses().forStatus(500).orElseThrow().description())
            .contains("Not found");
        assertThat(doc.webhooks()).singleElement().satisfies(w -> {
            assertThat(w.kind()).isEqualTo(dev.openapifn.model.ApiOperation.Kind.WEBHOOK);
            assertThat(w.path()).isEqualTo("newPet");
        });
        assertThat(doc.operation("newPetWebhook")).isPresent();
        assertThat(doc.operation("nope")).isEmpty();
        var petSchema = doc.schemas().deref(model.components().schemas().get("Pet"));
        assertThat(petSchema.document().toString()).endsWith("petstore/schemas/pet.yaml");
        assertThat(petSchema.required()).containsExactly("id", "name", "petType");
        assertThat(doc.schemas().documents()).hasSize(4);
        assertThat(doc.warnings()).isEmpty();
        assertThat(doc.uri().toString()).isEqualTo("classpath:/petstore/openapi.yaml");
    }

    @Test
    void effectiveServers() {
        var doc = ok(v31("""
            servers: [{url: /global}]
            paths:
              /a:
                servers: [{url: /item}]
                get: {responses: {'200': {description: ok}}}
                put: {servers: [{url: /op}], responses: {'200': {description: ok}}}
              /b:
                get: {responses: {'200': {description: ok}}}
            """));
        assertThat(doc.operations()).extracting(op -> op.servers().getFirst().url()).containsExactly("/item", "/op", "/global");
        assertThat(doc.operations().getFirst().displayName()).isEqualTo("GET /a");
        assertThat(ok(v31("paths: {}")).model().servers()).singleElement().satisfies(s -> assertThat(s.url()).isEqualTo("/"));
    }

    @Test
    void infoAndServers() {
        assertThat(messages("""
            openapi: 3.1.0
            info: {title: t, version: v, contact: {name: n, url: u, email: e}, license: {name: l, identifier: i, url: u}}
            paths: {}
            """)).containsExactlyInAnyOrder("License 'identifier' and 'url' are mutually exclusive");
        assertThat(messages(v31("""
            servers:
              - url: https://{a}.{b}.com
                variables:
                  a: {default: x, enum: []}
              - url: /{c}
                variables:
                  c: {default: z, enum: [x, y], description: d}
            paths: {}
            """))).containsExactlyInAnyOrder("Server variable 'enum' must not be empty",
            "Server variable 'b' is not defined", "Server variable default 'z' is not one of its enum values");
    }

    @Test
    void paths() {
        assertThat(messages(v31("""
            paths:
              x-ext: 1
              pets: {}
              /a/{id}:
                parameters: [{name: id, in: path, required: true, schema: {type: string}}]
              /a/{name}: {}
            """))).containsExactlyInAnyOrder("Path 'pets' must begin with '/'", "Path '/a/{name}' is equivalent to '/a/{id}'");
        var doc = ok(v31("paths:\n  x-ext: 1\n  /a: {}"));
        assertThat(doc.model().paths().extensions()).containsKey("x-ext");
        assertThat(messages(v31("""
            paths:
              /a/{id}:
                get:
                  parameters: [{name: other, in: path, required: true, schema: {type: string}}]
                  responses: {'200': {description: ok}}
            """))).containsExactlyInAnyOrder("GET /a/{id}: path parameter 'id' is not declared",
            "GET /a/{id}: path parameter 'other' does not occur in the path template");
    }

    @Test
    void pathItemReferencesMergeWithSiblings() {
        var doc = parse(v31("""
            paths:
              /a:
                $ref: other.yaml#/item
                summary: local
                parameters: [{name: q, in: query, schema: {type: string}, description: local}]
                post: {responses: {'200': {description: ok}}}
              /b:
                $ref: other.yaml#/item
            """), Map.of("other.yaml", """
            item:
              summary: base
              description: base description
              servers: [{url: /base}]
              parameters:
                - {name: q, in: query, schema: {type: string}, description: base}
                - {name: r, in: query, schema: {type: string}}
              get: {responses: {'200': {description: ok}}}
              post: {summary: base post, responses: {'200': {description: ok}}}
              x-base: true
            """)).orElseThrow(d -> new AssertionError(d.toString()));
        var a = doc.model().paths().items().get("/a");
        assertThat(a.summary()).contains("local");
        assertThat(a.description()).contains("base description");
        assertThat(a.servers()).singleElement().satisfies(s -> assertThat(s.url()).isEqualTo("/base"));
        assertThat(a.operations().keySet()).containsExactly("GET", "POST");
        assertThat(a.operation("post").orElseThrow().summary()).isEmpty();
        assertThat(a.parameters()).extracting(p -> p.name() + ":" + p.description().orElse("")).containsExactly("r:", "q:local");
        assertThat(a.extensions()).containsKey("x-base");
        var b = doc.model().paths().items().get("/b");
        assertThat(b.summary()).contains("base");
        var local = ok(v31("paths:\n  /a:\n    servers: [{url: /mine}]\n    $ref: '#/components/pathItems/P'\ncomponents:\n  pathItems:\n    P: {servers: [{url: /theirs}]}"));
        assertThat(local.model().paths().items().get("/a").servers()).singleElement().satisfies(s -> assertThat(s.url()).isEqualTo("/mine"));
        assertThat(messages(v31("paths:\n  /a:\n    $ref: missing.yaml#/x"))).singleElement()
            .satisfies(m -> assertThat(m).startsWith("Cannot resolve reference 'missing.yaml#/x'"));
    }

    @Test
    void operationsAndAdditionalOperations() {
        var doc = ok("""
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /a:
                query: {operationId: q, deprecated: true, responses: {'200': {description: ok}}}
                additionalOperations:
                  LINK: {operationId: link, responses: {'200': {description: ok}}}
            """);
        assertThat(doc.model().paths().items().get("/a").operations().keySet()).containsExactly("QUERY", "LINK");
        assertThat(doc.operation("q").orElseThrow().operation().deprecated()).isTrue();
        assertThat(messages("""
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /a:
                additionalOperations:
                  link: {responses: {'200': {description: ok}}}
                  GET: {responses: {'200': {description: ok}}}
            """)).containsExactlyInAnyOrder("Additional operation method 'link' must be upper case and not one of the fixed methods",
            "Additional operation method 'GET' must be upper case and not one of the fixed methods");
        assertThat(messages(v31("""
            paths:
              /a:
                get: {operationId: x, responses: {'200': {description: ok}}}
                put: {operationId: x}
                parameters: [{name: q, in: query, schema: {}}, {name: q, in: query, schema: {}}]
            """))).containsExactlyInAnyOrder("Duplicate parameter 'q' in query", "Duplicate operationId 'x'");
        assertThat(messages(v30("paths:\n  /a:\n    get: {}"))).containsExactlyInAnyOrder("Missing required field 'responses'");
        assertThat(messages(v31("paths:\n  /a:\n    get: {responses: {}}"))).containsExactlyInAnyOrder("Responses Object should contain at least one response");
        assertThat(messages(v31("paths:\n  /a:\n    get: {externalDocs: {description: d}, responses: {'200': {description: ok}}}")))
            .containsExactlyInAnyOrder("Missing required field 'url'");
        assertThat(messages(v31("""
            paths:
              /a:
                get:
                  parameters: [{name: h, in: header, schema: {}}, {name: H, in: header, schema: {}}]
                  responses: {'200': {description: ok}}
            """))).containsExactlyInAnyOrder("Duplicate parameter 'h' in header");
    }

    @Test
    void parameters() {
        assertThat(messages(v31("""
            paths:
              /a/{p}:
                get:
                  parameters:
                    - {in: query, schema: {}}
                    - {name: x}
                    - {name: x, in: body}
                    - {name: s, in: query, style: weird, schema: {}}
                    - {name: s2, in: query, style: matrix, schema: {}}
                    - {name: p, in: path, schema: {}}
                    - {name: Accept, in: header, schema: {}}
                    - {name: both, in: query, schema: {}, content: {application/json: {}}}
                    - {name: many, in: query, content: {application/json: {}, text/plain: {}}}
                    - {name: none, in: query}
                  responses: {'200': {description: ok}}
            """))).containsExactlyInAnyOrder(
            "Missing required field 'name'", "Missing required field 'in'", "Invalid parameter location 'body'",
            "Invalid parameter style 'weird'", "Style 'matrix' is not allowed for query parameters", "Path parameter 'p' must be required",
            "Header parameter 'Accept' is ignored; it is controlled by other OpenAPI constructs", "'schema' and 'content' are mutually exclusive",
            "'content' must contain exactly one media type", "Should define either 'schema' or 'content'");
        var doc = ok("""
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /a/{p}:
                get:
                  parameters:
                    - {name: p, in: path, required: true, style: label, schema: {type: string}, example: x, examples: {e: {value: 1}}}
                    - {name: q, in: query, style: deepObject, explode: true, allowReserved: true, allowEmptyValue: true, deprecated: true, schema: {}}
                    - {name: qs, in: querystring, content: {application/x-www-form-urlencoded: {schema: {type: object}}}}
                    - {name: c, in: cookie, style: cookie, schema: {}}
                    - {name: c2, in: cookie, schema: {}}
                    - {name: h, in: header, schema: {}}
                  responses: {'200': {description: ok}}
            """);
        var params = doc.operations().getFirst().parameters();
        assertThat(params).extracting(p -> p.style() + "/" + p.explode())
            .containsExactly("LABEL/false", "DEEP_OBJECT/true", "FORM/true", "COOKIE/true", "FORM/true", "SIMPLE/false");
        assertThat(params.get(0).example()).contains(JsonString.of("x"));
        assertThat(params.get(0).examples().get("e").value()).isPresent();
        assertThat(params.get(1).allowReserved()).isTrue();
        assertThat(params.get(1).allowEmptyValue()).isTrue();
        assertThat(params.get(1).deprecated()).isTrue();
        assertThat(params.get(2).effectiveSchema()).isPresent();
        assertThat(params.get(4).effectiveSchema()).isPresent();
        assertThat(messages("""
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /a:
                get:
                  parameters:
                    - {name: qs, in: querystring, schema: {}}
                    - {name: h, in: header, style: form, schema: {}}
                    - {name: c, in: cookie, style: simple, schema: {}}
                  responses: {'200': {description: ok}}
            """)).containsExactlyInAnyOrder("A querystring parameter must use 'content'", "Style 'form' is not allowed for header parameters",
            "Style 'simple' is not allowed for cookie parameters");
    }

    @Test
    void headersBodiesMediaTypesAndEncodings() {
        var doc = ok("""
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /a:
                post:
                  requestBody:
                    description: d
                    required: true
                    content:
                      multipart/form-data:
                        schema: {type: object}
                        encoding:
                          file: {contentType: image/png, headers: {X-Rate: {schema: {type: integer}, required: true}}, style: form, explode: true, allowReserved: true}
                      multipart/mixed:
                        itemSchema: {type: string}
                        prefixEncoding: [{contentType: text/plain}]
                        itemEncoding: {contentType: application/json, encoding: {a: {}}, prefixEncoding: [{}], itemEncoding: {}}
                  responses:
                    '200':
                      summary: s
                      headers:
                        X-Next: {description: next, deprecated: true, explode: true, schema: {type: string}, example: x, examples: {a: {value: 1}}}
                        X-Json: {content: {application/json: {schema: {}}}}
                      links:
                        self: {operationId: x, parameters: {id: $response.body#/id}, requestBody: 1, description: d, server: {url: /s}}
            """);
        var op = doc.operations().getFirst().operation();
        var body = op.requestBody().orElseThrow();
        assertThat(body.required()).isTrue();
        var encoding = body.content().get("multipart/form-data").encoding().get("file");
        assertThat(encoding.contentType()).contains("image/png");
        assertThat(encoding.style()).contains(ParameterStyle.FORM);
        assertThat(encoding.explode()).contains(true);
        assertThat(encoding.headers().get("X-Rate").required()).isTrue();
        var mixed = body.content().get("multipart/mixed");
        assertThat(mixed.itemSchema()).isPresent();
        assertThat(mixed.prefixEncoding()).hasSize(1);
        assertThat(mixed.itemEncoding().orElseThrow().encoding()).containsKey("a");
        var response = op.responses().forStatus(200).orElseThrow();
        assertThat(response.summary()).contains("s");
        assertThat(response.description()).isEmpty();
        assertThat(response.headers().get("X-Next").asParameter("X-Next").in()).isEqualTo(ParameterLocation.HEADER);
        assertThat(response.links().get("self").parameters()).containsKey("id");
        assertThat(messages(v31("""
            paths:
              /a:
                post:
                  requestBody: {description: d}
                  responses:
                    '200':
                      headers:
                        X: {style: form, schema: {}}
                      content:
                        text/plain: {encoding: {a: {style: nope}}}
            """))).containsExactlyInAnyOrder("Missing required field 'content'", "Missing required field 'description'", "Header style must be 'simple'",
            "Invalid encoding style 'nope'");
    }

    @Test
    void responses() {
        var doc = ok(v31("""
            paths:
              /a:
                get:
                  responses:
                    x-ext: 1
                    2XX: {description: range}
                    '404': {description: missing}
                    default: {description: fallback}
            """));
        var responses = doc.operations().getFirst().operation().responses();
        assertThat(responses.forStatus(201).orElseThrow().description()).contains("range");
        assertThat(responses.forStatus(404).orElseThrow().description()).contains("missing");
        assertThat(responses.forStatus(500).orElseThrow().description()).contains("fallback");
        assertThat(responses.extensions()).containsKey("x-ext");
        assertThat(messages(v31("paths:\n  /a:\n    get:\n      responses:\n        '600': {description: x}\n        '200': {description: ok}")))
            .containsExactlyInAnyOrder("Invalid response status code '600'");
        assertThat(ok(v31("paths:\n  /a:\n    get:\n      responses:\n        '200': {description: ok}")).operations().getFirst()
            .operation().responses().forStatus(500)).isEmpty();
    }

    @Test
    void pointerReferencesInEmbeddedResourcesFallBackToTheDocument() {
        var doc = ok(v31("""
            paths:
              /a:
                get:
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            $id: 'https://example.com/schemas/Wrapper'
                            $defs:
                              item: {$ref: '#/components/schemas/Pet'}
                            $ref: '#/components/schemas/Pet'
            components:
              schemas:
                Pet: {type: object, required: [name]}
            """));
        assertThat(doc.warnings()).extracting(Diagnostic::message).containsOnly("'#/components/schemas/Pet' does not exist in the schema resource "
            + "https://example.com/schemas/Wrapper; resolved against the document instead");
        var schema = doc.operations().getFirst().operation().responses().forStatus(200).orElseThrow().content().get("application/json").schema()
            .orElseThrow();
        assertThat(doc.schemas().deref(schema).required()).containsExactly("name");
        var validator = new dev.openapifn.schema.SchemaValidator(doc.schemas());
        assertThat(validator.validate(schema, dev.openapifn.json.JsonObject.EMPTY, dev.openapifn.schema.Direction.RESPONSE)).isNotEmpty();
        assertThat(doc.schemas().resolve(schema, "#nowhere")).isEmpty();
    }

    @Test
    void callbacksExamplesLinksAndTags() {
        var doc = ok(v31("""
            security: [{key: []}]
            paths:
              /a:
                post:
                  callbacks:
                    onEvent:
                      x-ext: 1
                      '{$request.body#/url}':
                        post: {operationId: eventHappened, responses: {'200': {description: ok}}}
                  responses: {'200': {description: ok}}
            webhooks:
              hook:
                post:
                  callbacks:
                    onAck:
                      '{$request.header.X-Ack}':
                        put: {security: [{key: []}], responses: {'204': {description: acknowledged}}}
                  responses: {'200': {description: ok}}
            tags: [{name: a, summary: s, description: d, externalDocs: {url: u}, parent: p, kind: nav}]
            externalDocs: {url: https://docs}
            components:
              securitySchemes:
                key: {type: apiKey, name: k, in: header}
              examples:
                full: {summary: s, description: d, value: 1}
                data: {dataValue: {a: 1}, serializedValue: 'a=1'}
                external: {externalValue: https://x}
            """));
        assertThat(doc.callbacks()).satisfiesExactly(event -> {
            assertThat(event.owner()).isEqualTo(doc.operations().getFirst());
            assertThat(event.name()).isEqualTo("onEvent");
            assertThat(event.operation().kind()).isEqualTo(dev.openapifn.model.ApiOperation.Kind.CALLBACK);
            assertThat(event.operation().path()).isEqualTo("{$request.body#/url}");
            assertThat(event.operation().security()).isEmpty();
        }, ack -> {
            assertThat(ack.owner()).isEqualTo(doc.webhooks().getFirst());
            assertThat(ack.operation().method()).isEqualTo("PUT");
            assertThat(ack.operation().security()).hasSize(1);
        });
        assertThat(doc.operations().getFirst().security()).hasSize(1);
        assertThat(doc.webhooks().getFirst().security()).isEmpty();
        assertThat(doc.allOperations()).extracting(dev.openapifn.model.ApiOperation::kind).containsExactly(dev.openapifn.model.ApiOperation.Kind.PATH,
            dev.openapifn.model.ApiOperation.Kind.WEBHOOK, dev.openapifn.model.ApiOperation.Kind.CALLBACK, dev.openapifn.model.ApiOperation.Kind.CALLBACK);
        assertThat(doc.operation("eventHappened")).contains(doc.callbacks().getFirst().operation());
        var ack = doc.callbacks().get(1);
        assertThat(doc.callbacks().getFirst().id()).isEqualTo("eventHappened");
        assertThat(ack.id()).isEqualTo("POST hook onAck PUT");
        assertThat(doc.operation("POST hook onAck PUT")).contains(ack.operation());
        assertThat(doc.operation("POST /a")).contains(doc.operations().getFirst());
        assertThat(doc.operation("POST hook")).contains(doc.webhooks().getFirst());
        assertThat(doc.operation("PUT {$request.header.X-Ack}")).isEmpty();
        var path = doc.operations().getFirst();
        assertThat(List.of(path.requestDirection(), path.responseDirection()))
            .containsExactly(dev.openapifn.schema.Direction.REQUEST, dev.openapifn.schema.Direction.RESPONSE);
        assertThat(List.of(ack.operation().requestDirection(), ack.operation().responseDirection()))
            .containsExactly(dev.openapifn.schema.Direction.RESPONSE, dev.openapifn.schema.Direction.REQUEST);
        var callback = doc.operations().getFirst().operation().callbacks().get("onEvent");
        assertThat(callback.expressions()).containsOnlyKeys("{$request.body#/url}");
        assertThat(callback.extensions()).containsKey("x-ext");
        assertThat(doc.model().tags().getFirst().parent()).contains("p");
        assertThat(doc.model().externalDocs().orElseThrow().url()).isEqualTo("https://docs");
        var examples = doc.model().components().examples();
        assertThat(examples.get("full").data()).isPresent();
        assertThat(examples.get("data").data().orElseThrow().toJson()).isEqualTo("{\"a\":1}");
        assertThat(examples.get("external").data()).isEmpty();
        assertThat(messages(v31("""
            components:
              examples:
                a: {value: 1, dataValue: 1, externalValue: x}
                b: {serializedValue: x, externalValue: y}
              links:
                none: {}
                both: {operationId: a, operationRef: b}
            """))).containsExactlyInAnyOrder("Example 'value' is mutually exclusive with [dataValue, externalValue]",
            "Example 'serializedValue' and 'externalValue' are mutually exclusive", "Exactly one of 'operationRef' and 'operationId' is required",
            "Exactly one of 'operationRef' and 'operationId' is required");
    }

    @Test
    void securitySchemes() {
        var doc = ok(v31("""
            components:
              securitySchemes:
                key: {type: apiKey, name: k, in: cookie, description: d, deprecated: true}
                basic: {type: http, scheme: basic}
                bearer: {type: http, scheme: bearer, bearerFormat: JWT}
                mtls: {type: mutualTLS}
                oidc: {type: openIdConnect, openIdConnectUrl: https://id}
                oauth:
                  type: oauth2
                  oauth2MetadataUrl: https://meta
                  flows:
                    implicit: {authorizationUrl: https://a, scopes: {read: r}}
                    password: {tokenUrl: https://t, refreshUrl: https://r, scopes: {}}
                    clientCredentials: {tokenUrl: https://t, scopes: {}}
                    authorizationCode: {authorizationUrl: https://a, tokenUrl: https://t, scopes: {}}
                    deviceAuthorization: {deviceAuthorizationUrl: https://d, tokenUrl: https://t, scopes: {write: 1}}
            security:
              - key: []
                https://example.com/other#/components/securitySchemes/x: []
            """));
        var schemes = doc.model().components().securitySchemes();
        assertThat(schemes.get("key")).isInstanceOfSatisfying(SecurityScheme.ApiKey.class, k -> {
            assertThat(k.in()).isEqualTo(ParameterLocation.COOKIE);
            assertThat(k.deprecated()).isTrue();
            assertThat(k.description()).contains("d");
        });
        assertThat(schemes.get("bearer")).isInstanceOfSatisfying(SecurityScheme.Http.class, h -> assertThat(h.bearerFormat()).contains("JWT"));
        assertThat(schemes.get("mtls")).isInstanceOf(SecurityScheme.MutualTls.class);
        assertThat(schemes.get("oidc")).isInstanceOf(SecurityScheme.OpenIdConnect.class);
        assertThat(schemes.get("oauth")).isInstanceOfSatisfying(SecurityScheme.OAuth2.class, o -> {
            assertThat(o.oauth2MetadataUrl()).contains("https://meta");
            assertThat(o.flows().implicit().orElseThrow().scopes()).containsEntry("read", "r");
            assertThat(o.flows().deviceAuthorization().orElseThrow().scopes()).containsEntry("write", "");
        });
        assertThat(messages(v31("""
            components:
              securitySchemes:
                a: {type: apiKey, name: k, in: path}
                b: {type: oauth2}
                c: {type: magic}
                d: {description: no type}
                e: {type: oauth2, flows: {implicit: {}}}
            security:
              - undefined: []
              - bad: [1]
              - worse: x
            """))).containsExactlyInAnyOrder("API key location must be query, header or cookie", "Missing required field 'flows'",
            "Invalid security scheme type 'magic'", "Missing required field 'type'", "Missing required field 'authorizationUrl'",
            "Missing required field 'scopes'", "Security requirement scopes must be an array of strings",
            "Security requirement scopes must be an array of strings", "Security scheme 'undefined' is not defined in components");
        assertThat(messages(v31("""
            paths:
              /a:
                get:
                  security: [{missing: []}]
                  responses: {'200': {description: ok}}
            """))).containsExactlyInAnyOrder("Security scheme 'missing' is not defined in components");
    }

    @Test
    void schemaPositions() {
        assertThat(messages(v30("paths: {}\ncomponents: {schemas: {A: true, B: 1}}")))
            .containsExactlyInAnyOrder("A schema must be an object", "A schema must be an object");
        assertThat(messages(v31("components: {schemas: {A: true, B: 1}}"))).containsExactlyInAnyOrder("A schema must be an object or a boolean");
        var doc = parse(v31("components:\n  schemas:\n    A: {$ref: 'frag.yaml#/S'}\n    B: {$ref: 'v30.yaml#/components/schemas/C'}"),
            Map.of("frag.yaml", "S: {type: string}", "v30.yaml", v30("paths: {}\ncomponents: {schemas: {C: {type: string}}}")))
            .orElseThrow(d -> new AssertionError(d.toString()));
        assertThat(doc.schemas().deref(doc.model().components().schemas().get("A")).dialect()).isEqualTo(Dialect.DRAFT_2020_12);
        var mixed = parse(v31("""
            jsonSchemaDialect: http://json-schema.org/draft-07/schema#
            paths:
              /a:
                $ref: 'other.yaml#/components/pathItems/P'
            """), Map.of("other.yaml", """
            openapi: 3.0.3
            info: {title: t, version: v}
            paths: {}
            components:
              pathItems:
                P:
                  get:
                    parameters: [{name: q, in: query, schema: {type: string}}]
                    responses: {'200': {description: ok}}
            """)).orElseThrow(d -> new AssertionError(d.toString()));
        assertThat(mixed.operations().getFirst().parameters().getFirst().schema().orElseThrow().dialect()).isEqualTo(Dialect.OAS_3_0);
        var plain = parse(v31("""
            jsonSchemaDialect: http://json-schema.org/draft-07/schema#
            paths:
              /a:
                $ref: 'other.yaml#/P'
            """), Map.of("other.yaml", "P:\n  get:\n    parameters: [{name: q, in: query, schema: {type: string}}]\n"
            + "    responses: {'200': {description: ok}}\n"))
            .orElseThrow(d -> new AssertionError(d.toString()));
        assertThat(plain.operations().getFirst().parameters().getFirst().schema().orElseThrow().dialect()).isEqualTo(Dialect.DRAFT_7);
        var dialected = parse(v31("paths:\n  /a:\n    $ref: 'other.yaml#/paths/~1a'"), Map.of("other.yaml", v31("""
            jsonSchemaDialect: https://json-schema.org/draft/2019-09/schema
            paths:
              /a:
                get:
                  parameters: [{name: q, in: query, schema: {type: string}}]
                  responses: {'200': {description: ok}}
            """))).orElseThrow(d -> new AssertionError(d.toString()));
        assertThat(dialected.operations().getFirst().parameters().getFirst().schema().orElseThrow().dialect()).isEqualTo(Dialect.DRAFT_2019_09);
    }

    @Test
    void references() {
        var doc = ok(v31("""
            components:
              parameters:
                Base: {name: q, in: query, description: original, schema: {type: string}}
                Overridden: {$ref: '#/components/parameters/Base', description: changed, summary: ignored}
                Chained: {$ref: '#/components/parameters/Overridden'}
              responses:
                R: {description: base}
                S: {$ref: '#/components/responses/R', summary: short}
              mediaTypes:
                M: {schema: {type: string}}
                N: {$ref: '#/components/mediaTypes/M', description: no effect}
            """));
        var params = doc.model().components().parameters();
        assertThat(params.get("Overridden").description()).contains("changed");
        assertThat(params.get("Chained").description()).contains("changed");
        assertThat(doc.model().components().responses().get("S").summary()).contains("short");
        assertThat(doc.model().components().mediaTypes().get("N").schema()).isPresent();
        var v30 = parse(v30("""
            paths: {}
            components:
              parameters:
                Base: {name: q, in: query, description: original, schema: {type: string}}
                Overridden: {$ref: '#/components/parameters/Base', description: changed}
            """), Map.of()).orElseThrow(d -> new AssertionError(d.toString()));
        assertThat(v30.model().components().parameters().get("Overridden").description()).contains("original");
        assertThat(v30.warnings()).extracting(Diagnostic::message).containsExactly("Field 'description' next to '$ref' is ignored");
        assertThat(messages(v31("""
            components:
              parameters:
                A: {$ref: 1}
                B: {$ref: '#/components/parameters/C'}
                C: {$ref: '#/components/parameters/B'}
                D: {$ref: '#/components/parameters/Missing'}
                E: {$ref: 'other.yaml'}
                F: {$ref: '#anchor'}
                G: {$ref: '%zz'}
                H: {$ref: '#/components/parameters/A', other: x}
                I: {$ref: '#/info/title'}
            """), Map.of("other.yaml", "a: ["))).containsExactlyInAnyOrder(
            "'$ref' must be a string",
            "Circular reference to mem:/api.yaml#/components/parameters/B",
            "Circular reference to mem:/api.yaml#/components/parameters/C",
            "Reference target '#/components/parameters/Missing' does not exist",
            "Cannot resolve reference 'other.yaml': Invalid YAML: expected the node content, but found '<stream end>'",
            "Reference '#anchor' must use a JSON pointer fragment",
            "Invalid URI reference '%zz': Malformed escape pair",
            "Field 'other' next to '$ref' is ignored",
            "'$ref' must be a string",
            "Parameter Object must be an object");
    }

    @Test
    void fieldTypeErrors() {
        assertThat(messages("""
            openapi: 3.1.0
            info: {title: 1, version: v}
            paths:
              /a:
                get:
                  deprecated: maybe
                  tags: [1]
                  parameters: x
                  callbacks: []
                  responses: {'200': {description: ok}}
            """)).containsExactlyInAnyOrder("Field 'title' must be a string", "Field 'tags' must contain only strings", "Field 'deprecated' must be a boolean",
            "Field 'parameters' must be an array", "Field 'callbacks' must be an object");
        assertThat(messages(v31("components:\n  schemas: {}\n  x-ext: 1"))).isEmpty();
        assertThat(ok(v31("components:\n  schemas: {}\n  x-ext: 1")).model().components().extensions()).containsKey("x-ext");
        assertThat(ok(v31("components:\n  schemas:\n    x-weird-name: {type: string}")).model().components().schemas()).containsKey("x-weird-name");
        assertThat(ok(v31("paths: {}")).model().components().schemas()).isEmpty();
        assertThat(Optional.of(ok(v31("paths: {}\nx-top: 1")).model().extensions())).hasValueSatisfying(e -> assertThat(e).containsKey("x-top"));
    }

    @Test
    void componentKindsAndVariants() {
        var doc = ok(v31("""
            components:
              requestBodies:
                B: {content: {text/plain: {schema: {type: string}, examples: {e: {value: x}}}}}
              headers:
                H: {style: simple, schema: {type: string}}
              callbacks:
                C: {'{$url}': {post: {responses: {default: {description: any}}}}}
              securitySchemes:
                q: {type: apiKey, name: k, in: query}
                h: {type: apiKey, name: k, in: header}
            servers:
              - url: /{v}
                variables:
                  v: {default: x}
            security:
              - q: [read]
              - other.yaml#/components/securitySchemes/x: []
            """));
        var components = doc.model().components();
        assertThat(components.requestBodies().get("B").content().get("text/plain").examples()).containsKey("e");
        assertThat(components.headers()).containsKey("H");
        assertThat(components.callbacks().get("C").expressions().get("{$url}").operation("POST").orElseThrow().responses()
            .forStatus(201)).isPresent();
        assertThat(doc.model().security().getFirst().schemes().get("q")).containsExactly("read");
        assertThat(doc.model().servers().getFirst().defaultUrl()).isEqualTo("/x");
        assertThat(messages(v31("components: {schemas: {}}"))).isEmpty();
        assertThat(messages(v31("webhooks: {}"))).isEmpty();
        assertThat(messages("openapi: 3.1.0\ninfo: {title: t, version: v, license: {name: MIT, url: https://mit}}\npaths: {}")).isEmpty();
        assertThat(messages(v31("""
            components:
              parameters:
                A: {name: a, in: query, schema: {}}
                B: {$ref: '#/components/parameters/A', x-note: fine, summary: allowed}
            """))).isEmpty();
        assertThat(messages(v30("""
            paths: {}
            components:
              parameters:
                A: {name: a, in: query, schema: {}}
                B: {$ref: '#/components/parameters/A', summary: ignored}
            """))).containsExactly("Field 'summary' next to '$ref' is ignored");
    }
}
