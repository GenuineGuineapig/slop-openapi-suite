package dev.openapifn.spring.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ValidatingResponseWrapperTest {

    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(new OpenApiLoader(ResourceLoaders.inMemory(Map.of()))
        .parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /events:
                get:
                  responses:
                    '200':
                      description: events
                      content:
                        application/jsonl: {itemSchema: {type: object, required: [n]}}
                        text/plain: {schema: {type: string}}
            """).orElseThrow(d -> new AssertionError(d.toString())));
    private static final OperationMatch MATCH = VALIDATOR.validateRequest(HttpRequest.of("GET", "/events")).match().orElseThrow();

    private final List<ValidationReport> logged = new ArrayList<>();

    private ValidatingResponseWrapper wrapper(MockHttpServletResponse response, boolean fail) {
        return new ValidatingResponseWrapper(response, VALIDATOR, MATCH, fail, logged::add);
    }

    @Test
    void streamsThroughWritersAndSingleBytes() throws IOException {
        var response = new MockHttpServletResponse();
        var wrapper = wrapper(response, true);
        wrapper.setContentType("application/jsonl");
        wrapper.getOutputStream().write('{');
        var writer = wrapper.getWriter();
        assertThat(wrapper.getWriter()).isSameAs(writer);
        writer.write("\"n\":1}\n{\"n\"");
        wrapper.flushBuffer();
        assertThat(response.getContentAsString()).isEqualTo("{\"n\":1}\n");
        assertThat(response.isCommitted()).isTrue();
        writer.write(":2}");
        assertThat(wrapper.streamed()).isTrue();
        assertThat(wrapper.finish()).isEmpty();
        assertThat(response.getContentAsString()).isEqualTo("{\"n\":1}\n{\"n\":2}");
        assertThat(logged).isEmpty();

        assertThat(wrapper.getOutputStream().isReady()).isTrue();
    }

    @Test
    void passesWriteListenersOn() throws IOException {
        var listeners = new ArrayList<WriteListener>();
        var target = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                listeners.add(listener);
            }

            @Override
            public void write(int b) {
            }
        };
        var response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return target;
            }
        };
        var wrapper = wrapper(response, true);
        wrapper.setContentType("application/jsonl");
        var listener = new WriteListener() {
            @Override
            public void onWritePossible() {
            }

            @Override
            public void onError(Throwable t) {
            }
        };
        wrapper.getOutputStream().setWriteListener(listener);
        assertThat(listeners).containsExactly(listener);
        assertThat(wrapper.getOutputStream().isReady()).isFalse();
    }

    @Test
    void endsTheStreamAtTheFirstInvalidItem() throws IOException {
        var response = new MockHttpServletResponse();
        var wrapper = wrapper(response, true);
        wrapper.setContentType("application/jsonl");
        wrapper.getOutputStream().write("{\"n\":1}\n{}\n{\"n\":3}\n".getBytes(StandardCharsets.UTF_8));
        wrapper.getOutputStream().flush();
        assertThat(wrapper.finish()).hasValueSatisfying(report -> assertThat(report.errors()).singleElement()
            .satisfies(issue -> assertThat(issue.location()).isEqualTo("body/1")));
        assertThat(response.getContentAsString()).isEqualTo("{\"n\":1}\n");
        assertThat(logged).hasSize(1);
        var lenient = new MockHttpServletResponse();
        var logging = wrapper(lenient, false);
        logging.setContentType("application/jsonl");
        logging.getOutputStream().write("{}\n".getBytes(StandardCharsets.UTF_8));
        assertThat(logging.finish()).isEmpty();
        assertThat(lenient.getContentAsString()).isEqualTo("{}\n");
        assertThat(logged).hasSize(2);
    }

    @Test
    void capturesOtherBodies() throws IOException {
        var response = new MockHttpServletResponse();
        var untyped = wrapper(response, true);
        untyped.getWriter().write("x");
        untyped.flushBuffer();
        assertThat(untyped.streamed()).isFalse();
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(untyped.getContentAsByteArray()).containsExactly('x');
        var text = wrapper(new MockHttpServletResponse(), true);
        text.setContentType("text/plain");
        text.getOutputStream().write('y');
        assertThat(text.streamed()).isFalse();
    }
}
