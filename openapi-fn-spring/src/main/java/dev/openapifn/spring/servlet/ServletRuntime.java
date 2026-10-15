package dev.openapifn.spring.servlet;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.RequestBinding;
import dev.openapifn.spring.StreamedBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Connects an {@link OpenApiRuntime} to Spring MVC: generated controllers bind servlet requests
 * and answer with {@link ResponseEntity}s through it.
 */
public final class ServletRuntime {

    /** Request attribute holding the request as it was read, with its body, for its binding. */
    public static final String MESSAGE_ATTRIBUTE = ServletRuntime.class.getName() + ".message";

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
        var message = streamed ? ServletMessages.head(request) : request.getAttribute(MESSAGE_ATTRIBUTE) instanceof HttpRequest read ? read
            : ServletMessages.toHttpRequest(request);
        var pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return runtime.bind(message, operationId, pattern == null ? null : pattern.toString(), request.getAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE),
            streamed ? () -> ServletMessages.input(request) : null);
    }

    /**
     * Which of {@code operationIds} (webhooks or callbacks a controller receives at one path with one
     * method) {@code request} is, see {@link OpenApiRuntime#receiver}. The request is read once: its
     * binding reuses it.
     */
    public String receiver(HttpServletRequest request, String... operationIds) {
        var streamed = ItemStreams.sequential(request.getContentType());
        HttpRequest message;
        if (streamed) {
            message = ServletMessages.head(request);
        } else {
            message = ServletMessages.toHttpRequest(request);
            request.setAttribute(MESSAGE_ATTRIBUTE, message);
        }
        return runtime.receiver(message, request.getAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE), streamed, List.of(operationIds));
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
