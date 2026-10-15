package dev.openapifn.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.validation.OpenApiValidator;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class ValidatingRequestFactoryTest {

    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(
        URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            servers: [{url: 'https://h'}]
            paths:
              /events:
                post:
                  operationId: importEvents
                  requestBody:
                    required: true
                    content:
                      application/jsonl:
                        itemSchema: {type: object, required: [n]}
                        schema: {type: array, minItems: 2, uniqueItems: true}
                      application/x-ndjson: {}
                  responses:
                    '200': {description: counted, content: {application/json: {schema: {type: object, required: [count]}}}}
                    '201': {description: echoed, content: {application/jsonl: {itemSchema: {type: object, required: [n]}}}}
              /pets:
                get:
                  responses:
                    '200': {description: ok, content: {application/json: {schema: {type: array}}}}
                post:
                  requestBody:
                    required: true
                    content: {application/json: {schema: {type: object, required: [name]}}}
                  responses:
                    '200': {description: ok}
            """).orElseThrow(d -> new AssertionError(d.toString())));

    /** Requests that record what is sent, and when: optionally streaming (taking a body to write while executing). */
    private static final class Delegate implements ClientHttpRequestFactory {
        final List<MockClientHttpRequest> requests = new ArrayList<>();
        final boolean streaming;
        MockClientHttpResponse response = response(HttpStatus.OK, "application/json", "{\"count\": 2}");

        Delegate(boolean streaming) {
            this.streaming = streaming;
        }

        @Override
        public org.springframework.http.client.ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            var request = streaming ? new Streaming(method, uri) : new MockClientHttpRequest(method, uri);
            request.setResponse(response);
            requests.add(request);
            return request;
        }

        String sent() {
            return requests.getLast().getBodyAsString(StandardCharsets.UTF_8);
        }
    }

    private static final class Streaming extends MockClientHttpRequest implements StreamingHttpOutputMessage {
        private @Nullable Body body;

        Streaming(HttpMethod method, URI uri) {
            super(method, uri);
        }

        @Override
        public void setBody(Body body) {
            this.body = body;
        }

        @Override
        protected ClientHttpResponse executeInternal() throws IOException {
            if (body != null) {
                body.writeTo(getBody());
            }
            return super.executeInternal();
        }
    }

    private static MockClientHttpResponse response(HttpStatus status, String contentType, String body) {
        var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().add("Content-Type", contentType);
        return response;
    }

    private static RestClient client(Delegate delegate, boolean failOnInvalidResponse) {
        return RestClient.builder().requestFactory(new OpenApiValidatingRequestFactory(delegate, VALIDATOR, failOnInvalidResponse)).build();
    }

    private static int stream(RestClient client, String body) {
        return client.post().uri("https://h/events").contentType(MediaType.parseMediaType("application/jsonl"))
            .body(out -> out.write(body.getBytes(StandardCharsets.UTF_8))).retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void streamedBodiesPassItemByItemOnceValid() {
        for (var streaming : List.of(true, false)) {
            var delegate = new Delegate(streaming);
            var client = client(delegate, true);
            assertThat(stream(client, "{\"n\":1}\n{\"n\":2}\n")).isEqualTo(200);
            assertThat(delegate.sent()).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
            assertThatThrownBy(() -> stream(client, "{\"n\":1}\n{}\n{\"n\":3}\n")).isInstanceOf(ClientValidationException.class)
                .hasMessageContaining("request.body.schema.required");
            assertThat(delegate.sent()).as("the invalid item is not sent").isEqualTo("{\"n\":1}\n");
            assertThatThrownBy(() -> stream(client, "{\"n\":1}\n{\"n\":1.0}\n")).hasMessageContaining("request.body.schema.uniqueItems");
            assertThatThrownBy(() -> stream(client, "{\"n\":1}\n")).hasMessageContaining("request.body.schema.minItems");
        }
    }

    @Test
    void otherBodiesAreValidatedWhole() {
        var delegate = new Delegate(true);
        delegate.response = response(HttpStatus.OK, "application/json", "");
        var client = client(delegate, true);
        assertThat(client.post().uri("https://h/pets").contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Rex")).retrieve().toBodilessEntity()
            .getStatusCode().value()).isEqualTo(200);
        assertThat(delegate.sent()).isEqualTo("{\"name\":\"Rex\"}");
        assertThatThrownBy(() -> client.post().uri("https://h/pets").contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().toBodilessEntity())
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.schema.required");
        assertThat(delegate.requests.getLast().getBodyAsBytes()).as("nothing is sent").isEmpty();
        // Bodies of media types without described items are not framed.
        delegate.response = response(HttpStatus.OK, "application/json", "{\"count\": 1}");
        assertThat(client.post().uri("https://h/events").contentType(MediaType.parseMediaType("application/x-ndjson"))
            .body(out -> out.write("{}\n".getBytes(StandardCharsets.UTF_8))).retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(delegate.sent()).isEqualTo("{}\n");
    }

    @Test
    void responsesAreValidated() throws IOException {
        var delegate = new Delegate(true);
        delegate.response = response(HttpStatus.CREATED, "application/jsonl", "{\"n\":1}\n{}\n");
        var strict = client(delegate, true);
        assertThatThrownBy(() -> strict.post().uri("https://h/events").contentType(MediaType.parseMediaType("application/jsonl"))
            .body(out -> out.write("{\"n\":1}\n{\"n\":2}\n".getBytes(StandardCharsets.UTF_8))).retrieve().body(String.class))
            .hasMessageContaining("response.body.schema.required");
        delegate.response = response(HttpStatus.OK, "application/json", "{}");
        assertThatThrownBy(() -> strict.get().uri("https://h/pets").retrieve().body(String.class))
            .hasMessageContaining("response.body.schema.type");
        delegate.response = response(HttpStatus.OK, "application/json", "{}");
        assertThat(client(delegate, false).get().uri("https://h/pets").retrieve().body(String.class)).isEqualTo("{}");
    }

    @Test
    void requestsWrittenDirectly() throws IOException {
        var delegate = new Delegate(false);
        var factory = new OpenApiValidatingRequestFactory(delegate, VALIDATOR, true);
        var streamed = factory.createRequest(URI.create("https://h/events"), HttpMethod.POST);
        streamed.getHeaders().setContentType(MediaType.parseMediaType("application/jsonl"));
        streamed.getAttributes().put(OpenApiHttpClient.OPERATION_ATTRIBUTE, "importEvents");
        streamed.getBody().write("{\"n\":1}\n{\"n\"".getBytes(StandardCharsets.UTF_8));
        streamed.getBody().write(':');
        streamed.getBody().write("2}\n".getBytes(StandardCharsets.UTF_8));
        streamed.getBody().flush();
        try (var response = streamed.execute()) {
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getStatusText()).isEqualTo("OK");
            assertThat(response.getBody().readAllBytes()).isEqualTo(response.getBody().readAllBytes());
        }
        assertThat(delegate.sent()).isEqualTo("{\"n\":1}\n{\"n\":2}\n");
        assertThat(streamed.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(streamed.getURI()).isEqualTo(URI.create("https://h/events"));

        var whole = factory.createRequest(URI.create("https://h/pets"), HttpMethod.POST);
        whole.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        whole.getBody().write("{\"name\":".getBytes(StandardCharsets.UTF_8));
        whole.getBody().write("\"Rex\"}".getBytes(StandardCharsets.UTF_8));
        delegate.response = response(HttpStatus.OK, "application/json", "");
        whole.execute().close();
        assertThat(delegate.sent()).isEqualTo("{\"name\":\"Rex\"}");

        var bodiless = factory.createRequest(URI.create("https://h/pets"), HttpMethod.POST);
        assertThatThrownBy(bodiless::execute).isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.missing");

        delegate.response = response(HttpStatus.CREATED, "application/jsonl", "{\"n\":1}\n");
        var closed = factory.createRequest(URI.create("https://h/events"), HttpMethod.POST);
        closed.getHeaders().setContentType(MediaType.parseMediaType("application/jsonl"));
        closed.getBody().write("{\"n\":1}\n{\"n\":2}\n".getBytes(StandardCharsets.UTF_8));
        closed.getBody().close();
        try (var response = closed.execute()) {
            var body = response.getBody();
            assertThat(body.markSupported()).isFalse();
            body.mark(1);
            assertThatThrownBy(body::reset).isInstanceOf(IOException.class).hasMessage("mark/reset not supported");
            assertThat(body.readAllBytes()).hasSize(8);
        }
        assertThat(delegate.sent()).isEqualTo("{\"n\":1}\n{\"n\":2}\n");

        var closing = factory.createRequest(URI.create("https://h/events"), HttpMethod.POST);
        closing.getHeaders().setContentType(MediaType.parseMediaType("application/jsonl"));
        var out = closing.getBody();
        out.write("{\"n\":1}\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(out::close).isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.schema.minItems");
    }
}
