package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.servlet.ServletRuntime;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.ValidationIssue;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/** Callbacks sent to one URL with one method are told apart by their requests. */
class ReceiverDispatchTest {

    private static final OpenApiRuntime RUNTIME = runtime();

    private static OpenApiRuntime runtime() {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /orders:
                post:
                  operationId: placeOrder
                  responses: {'201': {description: placed}}
                  callbacks:
                    shipped:
                      '{$request.body#/hook}':
                        post:
                          operationId: orderShipped
                          requestBody:
                            required: true
                            content: {application/json: {schema: {type: object, required: [trackingId], properties: {trackingId: {type: string}}}}}
                          responses: {'204': {description: ok}}
                    cancelled:
                      '{$request.body#/hook}':
                        post:
                          operationId: orderCancelled
                          requestBody:
                            required: true
                            content: {application/json: {schema: {type: object, required: [reason], properties: {reason: {type: string}}}}}
                          responses: {'204': {description: ok}}
                        delete:
                          operationId: orderForgotten
                          responses: {'204': {description: ok}}
                    streamed:
                      '{$request.body#/stream}':
                        post:
                          operationId: orderEvents
                          requestBody: {content: {application/jsonl: {itemSchema: {type: object}}}}
                          responses: {'204': {description: ok}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        return new OpenApiRuntime("orders", api, OpenApiValidator.of(api), JsonMapper.builder().build(), true, List.of(
            Map.entry("/hooks/orders", "orderCancelled"), Map.entry("/hooks/orders", "orderForgotten"), Map.entry("/hooks/orders", "orderShipped"),
            Map.entry("/hooks/stream", "orderEvents")));
    }

    private static HttpRequest post(String body) {
        return HttpRequest.of("POST", "/hooks/orders").withBody("application/json", body);
    }

    @Test
    void theRuntimeValidatesRequestsAsTheReceiverTheyAreValidFor() {
        assertThat(RUNTIME.validate(post("{\"reason\": \"out of stock\"}")).match().orElseThrow().operation().operationId()).contains("orderCancelled");
        assertThat(RUNTIME.validate(post("{\"trackingId\": \"T1\"}")).match().orElseThrow().operation().operationId()).contains("orderShipped");
        var neither = RUNTIME.validate(post("{}"));
        assertThat(neither.match().orElseThrow().operation().operationId()).as("the first one, with its findings").contains("orderShipped");
        assertThat(neither.report().issues()).extracting(ValidationIssue::key).containsExactly("request.body.schema.required");
        assertThat(RUNTIME.validate(HttpRequest.of("DELETE", "/hooks/orders")).match().orElseThrow().operation().operationId()).contains("orderForgotten");
        assertThat(RUNTIME.validate(HttpRequest.of("PUT", "/hooks/orders")).report().issues()).extracting(ValidationIssue::key)
            .containsExactly(IssueKeys.REQUEST_OPERATION_NOT_ALLOWED);
    }

    @Test
    void receiversAreChosenByRequest() {
        var ids = List.of("orderShipped", "orderCancelled");
        assertThat(RUNTIME.receiver(post("{\"reason\": \"x\"}"), null, false, ids)).isEqualTo("orderCancelled");
        assertThat(RUNTIME.receiver(post("{}"), null, false, ids)).isEqualTo("orderShipped");
        // An earlier validation of one of them is reused; others are not.
        var earlier = RUNTIME.validate(post("{\"reason\": \"x\"}"));
        assertThat(RUNTIME.receiver(post("{\"trackingId\": \"T1\"}"), earlier, false, ids)).isEqualTo("orderCancelled");
        var unrelated = RUNTIME.validate(HttpRequest.of("DELETE", "/hooks/orders"));
        assertThat(RUNTIME.receiver(post("{\"trackingId\": \"T1\"}"), unrelated, false, ids)).isEqualTo("orderShipped");
        assertThat(RUNTIME.receiver(post("{\"trackingId\": \"T1\"}"), "not a validation", false, ids)).isEqualTo("orderShipped");
        assertThat(RUNTIME.receiver(post("{}"), new dev.openapifn.validation.OpenApiValidator.RequestValidation(
            dev.openapifn.validation.ValidationReport.EMPTY, java.util.Optional.empty(), Map.of(), java.util.Optional.empty()), false, ids))
            .isEqualTo("orderShipped");
        // Streamed bodies cannot be inspected: their heads decide.
        assertThat(RUNTIME.receiver(HttpRequest.of("POST", "/hooks/orders").withHeader("Content-Type", "application/json"), null, true, ids))
            .isEqualTo("orderShipped");
    }

    @Test
    void servletRequestsAreReadOnce() {
        var servlet = new ServletRuntime(RUNTIME);
        var request = new MockHttpServletRequest("POST", "/hooks/orders");
        request.setContentType("application/json");
        request.setContent("{\"reason\": \"late\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(servlet.receiver(request, "orderShipped", "orderCancelled")).isEqualTo("orderCancelled");
        assertThat(servlet.bind(request, "orderCancelled").body(new tools.jackson.core.type.TypeReference<Map<String, String>>() { })).containsEntry("reason", "late");
        var streamed = new MockHttpServletRequest("POST", "/hooks/stream");
        streamed.setContentType("application/jsonl");
        streamed.setContent("{}\n".getBytes(StandardCharsets.UTF_8));
        assertThat(servlet.receiver(streamed, "orderEvents")).isEqualTo("orderEvents");
        assertThat(streamed.getAttribute(ServletRuntime.MESSAGE_ATTRIBUTE)).isNull();
    }
}
