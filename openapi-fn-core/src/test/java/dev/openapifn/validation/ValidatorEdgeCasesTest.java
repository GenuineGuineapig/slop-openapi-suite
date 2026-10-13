package dev.openapifn.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.json.Json;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Direction;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidatorEdgeCasesTest {

    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(new OpenApiLoader(ResourceLoaders.inMemory(Map.of()))
        .parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            servers: [{url: /global}]
            paths:
              /items:
                servers: [{url: /local}]
                get:
                  parameters:
                    - {name: obj, in: query, explode: false, schema: {type: object}}
                  responses:
                    '200':
                      description: ok
                      headers:
                        X-Json: {content: {application/json: {schema: {type: object}}}}
                      content:
                        application/json:
                          itemSchema: {type: string}
                    '201':
                      description: created
                      content:
                        application/json:
                          schema: {anyOf: [{type: string}, {type: integer}]}
                post:
                  requestBody:
                    content: {application/json: {schema: {type: object}}}
                  responses: {'204': {description: ok}}
            webhooks:
              ping:
                post: {responses: {'200': {description: ok}}}
            """).orElseThrow(d -> new AssertionError(d.toString())));

    private static List<String> keys(ValidationReport report) {
        return report.issues().stream().map(issue -> issue.key() + "@" + issue.location()).toList();
    }

    @Test
    void pathLevelServersAndParameters() {
        assertThat(keys(VALIDATOR.validateRequest(HttpRequest.of("GET", "/local/items?obj=a,1&other=2")).report())).isEmpty();
        assertThat(VALIDATOR.validateRequest(HttpRequest.of("GET", "/local/items?obj=a,1")).parameters())
            .containsEntry("query.obj", Json.of(Map.of("a", "1")));
        assertThat(keys(VALIDATOR.validateRequest(HttpRequest.of("GET", "/global/items")).report())).containsExactly("request.path.missing@path");
        assertThat(keys(VALIDATOR.validateRequest(HttpRequest.of("GET", "/items")).report())).isEmpty();
        assertThat(keys(VALIDATOR.validateRequest(HttpRequest.of("POST", "/local/items")).report())).isEmpty();
    }

    @Test
    void responsesWithContentHeadersItemSchemasAndCombinators() {
        var get = HttpRequest.of("GET", "/local/items");
        assertThat(keys(VALIDATOR.validateResponse(get, HttpResponse.of(200).withHeader("X-Json", "{").withBody("application/json", "{}"))))
            .containsExactly("response.header.invalid@header.X-Json");
        assertThat(keys(VALIDATOR.validateResponse(get, HttpResponse.of(200).withBody(new byte[0])))).containsExactly("response.body.missing@body");
        assertThat(keys(VALIDATOR.validateResponse(get, HttpResponse.of(200).withBody("application/json", "{}")))).isEmpty();
        assertThat(keys(VALIDATOR.validateResponse(get, HttpResponse.of(200).withBody("application/json", "[1]"))))
            .containsExactly("response.body.schema.type@body/0");
        var report = VALIDATOR.validateResponse(get, HttpResponse.of(201).withBody("application/json", "true"));
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.key()).isEqualTo("response.body.schema.anyOf");
            assertThat(issue.message()).isEqualTo("must match at least one schema in anyOf: /: must be of type string but was boolean; "
                + "/: must be of type integer but was boolean");
        });
    }

    @Test
    void webhookMethodMismatchAndValueValidation() {
        assertThat(keys(VALIDATOR.validateWebhookRequest("ping", HttpRequest.of("GET", "/")).report())).containsExactly("request.path.missing@webhook");
        assertThat(keys(VALIDATOR.validateWebhookRequest("ping", HttpRequest.of("POST", "/")).report())).isEmpty();
        var schema = VALIDATOR.api().operations().getLast().operation().requestBody().orElseThrow().content().get("application/json").schema().orElseThrow();
        assertThat(VALIDATOR.validateValue(schema, Json.of(1), Direction.REQUEST)).hasSize(1);
    }
}
