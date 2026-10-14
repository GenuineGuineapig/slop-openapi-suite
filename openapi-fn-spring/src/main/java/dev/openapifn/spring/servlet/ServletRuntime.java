package dev.openapifn.spring.servlet;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.RequestBinding;
import dev.openapifn.spring.StreamedBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Connects an {@link OpenApiRuntime} to Spring MVC: generated controllers bind servlet requests
 * and answer with {@link ResponseEntity}s through it.
 */
public final class ServletRuntime {

    private final OpenApiRuntime runtime;

    public ServletRuntime(OpenApiRuntime runtime) {
        this.runtime = runtime;
    }

    public OpenApiRuntime runtime() {
        return runtime;
    }

    /**
     * Validates and parses {@code request} for the operation {@code operationId}, reusing the
     * result of the validation filter when present. A body of a sequential media type is not read
     * here: {@link RequestBinding#items} streams it.
     *
     * @throws OpenApiValidationException when the request is invalid (and failing is enabled)
     */
    public RequestBinding bind(HttpServletRequest request, String operationId) {
        var streamed = ItemStreams.sequential(request.getContentType());
        var message = streamed ? ServletMessages.head(request) : ServletMessages.toHttpRequest(request);
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return runtime.bind(message, operationId, pattern == null ? null : pattern.toString(), request.getAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE),
            streamed ? () -> ServletMessages.input(request) : null);
    }

    /**
     * Maps {@code response} of operation {@code operationId} to a {@link ResponseEntity}. A
     * {@link Stream} body of a sequential media type is written item by item, flushing each.
     */
    public ResponseEntity<Object> respond(HttpServletRequest request, String operationId, ApiResponse response) {
        var reply = runtime.reply(request.getHeader("Accept"), operationId, response);
        var builder = ResponseEntity.status(reply.status()).headers(reply.headers());
        var body = reply.body();
        if (body == null) {
            return builder.build();
        }
        var type = Objects.requireNonNull(reply.contentType());
        if (body instanceof Stream<?> items && ItemFramer.isSequential(type)) {
            return builder.body(new StreamedBody(items, item -> runtime.encodeItem(item, type), BodyWriter.end(type)));
        }
        return builder.body(runtime.encode(reply));
    }
}
