package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.servlet.ServletRuntime;
import dev.openapifn.validation.OpenApiValidator;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class StreamingSupportTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ServletRuntime servlet(boolean failOnInvalid) {
        return new ServletRuntime(runtime(failOnInvalid));
    }

    private static OpenApiRuntime runtime(boolean failOnInvalid) {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /events:
                post:
                  operationId: publish
                  requestBody:
                    content:
                      application/jsonl: {itemSchema: {type: object, required: [n]}}
                      application/json: {schema: {type: array}}
                  responses:
                    '200':
                      description: ok
                      content:
                        application/jsonl: {}
            webhooks:
              events:
                post:
                  operationId: received
                  requestBody:
                    content:
                      application/jsonl: {itemSchema: {type: object}}
                  responses:
                    '200': {description: ok}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        return new OpenApiRuntime("events", api, OpenApiValidator.of(api), MAPPER, failOnInvalid, Map.of("/hooks/events", "received"));
    }

    private static MockHttpServletRequest request(String contentType, String body) {
        var request = new MockHttpServletRequest("POST", "/events");
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static InputStream failing() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }

            @Override
            public void close() throws IOException {
                throw new IOException("broken");
            }
        };
    }

    @Test
    void bindsStreamedRequestsWithoutTheFilter() {
        var runtime = servlet(true);
        assertThat(runtime.bind(request("application/jsonl", "{\"n\":1}\n{\"n\":2}\n"), "publish").items(Map.class))
            .containsExactly(Map.of("n", 1), Map.of("n", 2));
        assertThat(runtime.bind(request("application/jsonl", "{\"n\":1}\n"), "publish").body(String.class)).isEqualTo("{\"n\":1}\n");
        assertThat(runtime.bind(request("application/jsonl", ""), "publish").body(String.class)).isNull();
        assertThat(runtime.bind(request("application/json", ""), "publish").items(Map.class)).isEmpty();
        assertThatThrownBy(() -> runtime.bind(request("application/jsonl", "{}\n"), "publish").items(Map.class).toList())
            .isInstanceOf(OpenApiValidationException.class).hasMessageContaining("request.body.schema.required");
        var lenient = servlet(false);
        assertThat(lenient.bind(request("application/jsonl", "{}\n"), "publish").items(Map.class)).containsExactly(Map.of());
        // An undescribed sequential type is parsed without validation; unparseable items are skipped.
        assertThat(lenient.bind(request("application/x-ndjson", "{\"a\":1}\n{\n"), "publish").items(new TypeReference<Map<String, Integer>>() { }))
            .containsExactly(Map.of("a", 1));
        assertThat(runtime.runtime().validateHead(dev.openapifn.http.HttpRequest.of("POST", "/hooks/events").withHeader("Content-Type", "application/jsonl"))
            .report().isEmpty()).isTrue();
        assertThat(runtime.runtime().validateHead(dev.openapifn.http.HttpRequest.of("GET", "/hooks/events")).report().hasErrors()).isTrue();
    }

    @Test
    void bindsBodiesByMediaType() {
        var runtime = servlet(true);
        var lines = runtime.bind(request("application/jsonl", "{\"n\":1}\n"), "publish");
        assertThat(lines.hasContentType("application/jsonl")).isTrue();
        assertThat(lines.items("application/json", Map.class)).isNull();
        assertThat(lines.items("application/jsonl", Map.class)).containsExactly(Map.of("n", 1));
        var array = runtime.bind(request("application/json", "[{\"n\":2}]"), "publish");
        assertThat(array.items("application/jsonl", new TypeReference<Map<String, Integer>>() { })).isNull();
        assertThat(array.items("application/*", new TypeReference<Map<String, Integer>>() { })).containsExactly(Map.of("n", 2));
        assertThat((List<?>) array.body("application/jsonl", List.class)).isNull();
        assertThat((List<?>) array.body("application/json", List.class)).hasSize(1);
        assertThat(array.body("application/json", new TypeReference<List<Map<String, Integer>>>() { })).containsExactly(Map.of("n", 2));
        assertThat(array.body("text/plain", new TypeReference<List<Map<String, Integer>>>() { })).isNull();
        assertThat(array.hasContentType("nonsense")).isFalse();
        var accepting = request("application/json", "[]");
        accepting.addHeader("Accept", "text/plain, application/json;q=0");
        assertThat(servlet(false).bind(accepting, "publish").accept()).satisfies(accept -> {
            assertThat(accept.accepts("text/plain")).isTrue();
            assertThat(accept.accepts("application/json")).isFalse();
            assertThat(accept.accepts("nonsense")).isFalse();
            assertThat(accept).hasToString("text/plain, application/json;q=0");
        });
        assertThat(runtime.bind(request("application/json", "[]"), "publish").accept().accepts("image/png")).isTrue();
        assertThat(Accept.of("image/*").accepts("image/png")).isTrue();
        assertThat(Accept.parse(" , text/plain").ranges()).containsExactly("text/plain");
        var untyped = new MockHttpServletRequest("POST", "/events");
        assertThat(runtime.bind(untyped, "publish").hasContentType("application/json")).isFalse();
    }

    @Test
    void reportsUnreadableBodies() {
        var broken = new MockHttpServletRequest("POST", "/events") {
            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener listener) {
                    }

                    @Override
                    public int read() throws IOException {
                        throw new IOException("broken");
                    }
                };
            }
        };
        broken.setContentType("application/jsonl");
        assertThatThrownBy(() -> servlet(true).bind(broken, "publish").body(String.class)).isInstanceOf(UncheckedIOException.class);
        var unopenable = new jakarta.servlet.http.HttpServletRequestWrapper(request("application/jsonl", "")) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                throw new IOException("closed");
            }
        };
        assertThatThrownBy(() -> servlet(true).bind(unopenable, "publish").items(Map.class)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void readsItemsLazily() {
        var framer = ItemFramer.of(ContentType.parse("application/jsonl").orElseThrow()).orElseThrow();
        var parsed = new ArrayList<String>();
        try (var items = ItemStreams.read(new ByteArrayInputStream("1\n\n2".getBytes(StandardCharsets.UTF_8)), framer, frame -> {
            parsed.add(new String(frame.content(), StandardCharsets.UTF_8));
            return frame.blank() ? Optional.empty() : Optional.of(JsonValue.class.cast(dev.openapifn.json.JsonNumber.of(new java.math.BigDecimal(parsed.size()))));
        })) {
            var iterator = items.iterator();
            assertThat(iterator.next()).isNotNull();
            assertThat(parsed).containsExactly("1");
            assertThat(iterator.hasNext()).isTrue();
            iterator.next();
            assertThat(iterator.hasNext()).isFalse();
        }
        assertThatThrownBy(() -> ItemStreams.read(failing(), framer, frame -> Optional.empty()).toList()).isInstanceOf(UncheckedIOException.class);
        var unclosable = ItemStreams.read(failing(), framer, frame -> Optional.empty());
        assertThatThrownBy(unclosable::close).isInstanceOf(UncheckedIOException.class);
        assertThat(ItemStreams.sequential(null)).isFalse();
        assertThat(ItemStreams.sequential("application/json")).isFalse();
        assertThat(ItemStreams.sequential("text/event-stream")).isTrue();
    }

    @Test
    void writesStreamedBodies() throws IOException {
        var converter = new StreamedBodyConverter();
        assertThat(converter.canRead(StreamedBody.class, null)).isFalse();
        assertThat(converter.canWrite(StreamedBody.class, null)).isTrue();
        assertThat(converter.canWrite(String.class, null)).isFalse();
        assertThat(converter.getSupportedMediaTypes()).containsExactly(MediaType.ALL);
        assertThatThrownBy(() -> converter.read(StreamedBody.class, null)).isInstanceOf(UnsupportedOperationException.class);

        var message = new MockHttpOutputMessage();
        converter.write(body("a", "b"), MediaType.parseMediaType("application/jsonl"), message);
        assertThat(message.getBodyAsString()).isEqualTo("a\nb\n.");
        assertThat(message.getHeaders().getContentType()).hasToString("application/jsonl");
        var preset = new MockHttpOutputMessage();
        preset.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        converter.write(body(), MediaType.parseMediaType("application/jsonl"), preset);
        assertThat(preset.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        var untyped = new MockHttpOutputMessage();
        converter.write(body(), null, untyped);
        converter.write(body(), MediaType.ALL, untyped);
        assertThat(untyped.getHeaders().getContentType()).isNull();

        var servlet = new MockHttpServletResponse();
        converter.write(body("x"), MediaType.parseMediaType("application/jsonl"), new ServletServerHttpResponse(servlet));
        assertThat(servlet.getContentAsString()).isEqualTo("x\n.");
        assertThat(servlet.isCommitted()).isTrue();
        var plain = new ByteArrayOutputStream();
        body("y").writeTo(plain);
        assertThat(plain.toString(StandardCharsets.UTF_8)).isEqualTo("y\n.");
    }

    private static StreamedBody body(String... items) {
        return new StreamedBody(Stream.of(items), item -> (item + "\n").getBytes(StandardCharsets.UTF_8), ".".getBytes(StandardCharsets.UTF_8));
    }

    record Items(@Nullable Object body, @Nullable String contentType) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    @Test
    void respondsWithStreams() throws IOException {
        var runtime = servlet(true);
        var request = new MockHttpServletRequest("POST", "/events");
        var entity = runtime.respond(request, "publish", new Items(Stream.of(FilePart.of("f", "text/plain", new byte[] {65})), null));
        var out = new ByteArrayOutputStream();
        ((StreamedBody) entity.getBody()).writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("{\"filename\":\"f\"");
    }
}
