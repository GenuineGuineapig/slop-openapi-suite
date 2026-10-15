package dev.openapifn.generator.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.generator.java.TypeRef;
import dev.openapifn.generator.model.TypeMapper;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import java.net.URI;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Media types whose bodies have different Java types get a response variant, or a request component, each. */
class MediaTypeAlternativesTest {

    private static final dev.openapifn.parse.OpenApiDocument API = new OpenApiLoader(ResourceLoaders.inMemory(Map.of()))
        .parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /bodies:
                get:
                  operationId: bodies
                  responses:
                    '200':
                      description: the same schema referenced twice
                      content:
                        application/json: {schema: {$ref: '#/components/schemas/Pet'}}
                        application/xml: {schema: {$ref: '#/components/schemas/Pet'}}
                    '201':
                      description: the same inline schema twice
                      content:
                        application/json: {schema: {type: object, properties: {a: {type: integer}}}}
                        text/xml: {schema: {type: object, properties: {a: {type: integer}}}}
                    '202':
                      description: text without schemas
                      content:
                        text/plain: {}
                        text/csv: {}
                    '203':
                      description: binary content
                      content:
                        image/png: {}
                        application/pdf: {schema: {type: string, format: binary}}
                    '204':
                      description: streams of different items
                      content:
                        application/jsonl: {itemSchema: {$ref: '#/components/schemas/Pet'}}
                        text/event-stream: {itemSchema: {type: object, properties: {data: {type: string}}}}
                        application/x-ndjson: {}
                    '205':
                      description: different schemas of the same Java type, and of others
                      content:
                        application/json: {schema: {type: string, maxLength: 3}}
                        text/plain: {schema: {type: string}}
                        application/vnd.counter+json: {schema: {type: integer}}
                        '*/*': {}
                        'image/*': {schema: {type: string, format: binary}}
                        multipart/related: {schema: {type: string}}
              /accepting:
                get:
                  operationId: accepting
                  responses:
                    '200':
                      description: data or text
                      content:
                        application/json: {schema: {type: integer}}
                        text/plain: {schema: {type: string}}
              /forms:
                post:
                  operationId: forms
                  requestBody:
                    content:
                      application/json: {schema: {type: object, properties: {a: {type: integer}}}}
                      multipart/form-data: {schema: {type: object, properties: {b: {type: string}}}}
                      text/plain: {schema: {type: string}}
                  responses:
                    '204': {description: ok}
              /same:
                post:
                  operationId: same
                  requestBody:
                    content:
                      application/json: {schema: {type: string, maxLength: 3}}
                      text/plain: {schema: {type: string}}
                      application/x-www-form-urlencoded: {schema: {type: object, properties: {a: {type: integer}}}}
                      application/octet-stream: {schema: {type: string}}
                  responses:
                    '204': {description: ok}
            components:
              schemas:
                Pet: {type: object, properties: {name: {type: string}}}
            """).orElseThrow(d -> new AssertionError(d.toString()));

    private static OperationModel model(String id) {
        var mapper = new TypeMapper(API, "m");
        mapper.defineComponents();
        return OperationModel.of(API.operation(id).orElseThrow(), "a", mapper, new HashSet<>(), TypeRef.STREAM);
    }

    @Test
    void responsesOfDifferentTypesGetVariants() {
        var bodies = model("bodies");
        assertThat(bodies.variants()).extracting(variant -> variant.name() + variant.mediaType().map(type -> "=" + type).orElse(""))
            .containsExactly("Ok", "Created", "Accepted", "NonAuthoritativeInformation",
                "NoContent=application/jsonl", "NoContentEventStream=text/event-stream", "NoContentNdjson=application/x-ndjson",
                "ResetContent=application/json", "ResetContentCounterJson=application/vnd.counter+json", "ResetContentAny=*/*",
                "ResetContentImage=image/*");
        assertThat(bodies.variants()).filteredOn(OperationModel.VariantModel::alternative).hasSize(5);
        // multipart/related with a string schema is a String, like the primary: no variant of its own.
        assertThat(bodies.accept()).isPresent();
        assertThat(bodies.request()).isPresent();
        assertThat(bodies.requestProperties()).extracting(property -> property.javaName()).containsExactly("accept");
        assertThat(OperationModel.mediaLabel("application/x-ndjson")).isEqualTo("Ndjson");
    }

    @Test
    void requestBodiesOfDifferentTypesGetComponents() {
        var forms = model("forms");
        var body = forms.body().orElseThrow();
        // Form parts are encoded from a record of their own: they stay with the primary body.
        assertThat(body.alternatives()).extracting(OperationModel.AlternativeBody::contentType).containsExactly("text/plain");
        assertThat(forms.requestProperties()).extracting(property -> property.javaName() + ":" + property.nullable()).containsExactly("body:true",
            "bodyPlain:true");
        assertThat(forms.accept()).isEmpty();
        assertThat(model("accepting").variants()).extracting(OperationModel.VariantModel::name).containsExactly("Ok", "OkPlain");
        // The same Java type (String) needs no component of its own; a form and a binary body do.
        assertThat(model("same").body().orElseThrow().alternatives()).extracting(OperationModel.AlternativeBody::contentType)
            .containsExactly("application/x-www-form-urlencoded", "application/octet-stream");
    }
}
