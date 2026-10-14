package dev.openapifn.spring.client;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * Validates what a client sends and receives against an API description: requests before they
 * are sent (a streamed body item by item as it is written), responses as they are read (a streamed
 * body item by item). Request violations are {@link ClientValidationException}s; response
 * violations too, unless they are only logged. Used by {@link OpenApiHttpClient} and
 * {@link OpenApiClientValidationInterceptor}.
 */
public final class ClientValidator {

    private static final Log LOG = LogFactory.getLog(ClientValidator.class);

    private final OpenApiValidator validator;
    private final boolean failOnInvalidResponse;

    /** @param failOnInvalidResponse whether invalid responses are errors (else they are logged) */
    public ClientValidator(OpenApiValidator validator, boolean failOnInvalidResponse) {
        this.validator = validator;
        this.failOnInvalidResponse = failOnInvalidResponse;
    }

    /**
     * Validates a request: only its head when its body is {@code streamed}. A webhook or callback
     * (named by {@code operationId}) is validated against its operation, other requests are routed.
     *
     * @return the operation the request matched
     * @throws ClientValidationException when the request is invalid
     */
    public Optional<OperationMatch> request(String method, URI uri, Headers headers, byte @Nullable [] body, boolean streamed,
                                            @Nullable String operationId) {
        var message = new HttpRequest(method, uri.getRawPath(), Optional.ofNullable(uri.getRawQuery()).orElse(""), headers,
            Optional.ofNullable(body).filter(bytes -> bytes.length > 0));
        var operation = Optional.ofNullable(operationId).flatMap(id -> validator.api().operation(id)).filter(op -> op.kind() != ApiOperation.Kind.PATH);
        var validation = operation.map(op -> new OperationMatch(op, Map.of()))
            .map(match -> streamed ? validator.validateRequestHead(match, message) : validator.validateRequest(match, message))
            .orElseGet(() -> streamed ? validator.validateRequestHead(message) : validator.validateRequest(message));
        if (validation.report().hasErrors()) {
            throw new ClientValidationException("Request " + method + " " + uri + " violates the API description", validation.report());
        }
        return validation.match();
    }

    /**
     * A check of the encoded items of a streamed request body, in the order they are written:
     * throws a {@link ClientValidationException} on the first invalid item.
     */
    public Consumer<byte[]> requestItems(Optional<OperationMatch> match, ContentType type) {
        var items = match.flatMap(m -> validator.requestItems(m, type.toString()));
        if (items.isEmpty()) {
            return bytes -> { };
        }
        var framer = items.get().framer();
        return bytes -> framer.feed(bytes).forEach(frame -> {
            var report = items.get().validate(frame).report();
            if (report.hasErrors()) {
                throw new ClientValidationException("A request item violates the API description", report);
            }
        });
    }

    /** Validates a whole response; {@code description} names the request in messages. */
    public void response(Optional<OperationMatch> match, int status, Headers headers, byte[] body, String description) {
        match.ifPresent(m -> check(validator.validateResponse(m, new HttpResponse(status, headers, Optional.of(body).filter(bytes -> bytes.length > 0))),
            description));
    }

    /**
     * The body of a response of a sequential media type (see {@link dev.openapifn.spring.ItemStreams#sequential}),
     * validated item by item as it is read when the media type is described; its status, headers and
     * content type are validated now. Other responses are validated whole with {@link #response}.
     */
    public InputStream streamedResponse(Optional<OperationMatch> match, int status, Headers headers, InputStream body, String description) {
        if (match.isEmpty()) {
            return body;
        }
        check(validator.validateResponseHead(match.get(), new HttpResponse(status, headers, Optional.empty())), description);
        return headers.first("Content-Type").flatMap(contentType -> validator.responseItems(match.get(), status, contentType))
            .<InputStream>map(items -> new ValidatingInputStream(body, items, report -> check(report, description))).orElse(body);
    }

    /** Throws or logs the violations of a response (or of an item of it). */
    private void check(ValidationReport report, String description) {
        if (report.hasErrors() && failOnInvalidResponse) {
            throw new ClientValidationException("Response of " + description + " violates the API description", report);
        }
        if (!report.isEmpty()) {
            LOG.warn("Response of " + description + " violates the API description:\n" + report);
        }
    }

    /** Spring headers as openapi-fn's. */
    public static Headers headers(HttpHeaders source) {
        var headers = Headers.EMPTY;
        for (var entry : source.headerSet()) {
            for (var value : entry.getValue()) {
                headers = headers.with(entry.getKey(), value);
            }
        }
        return headers;
    }

    /** Validates the items of a body as they are read. */
    private static final class ValidatingInputStream extends FilterInputStream {

        private final OpenApiValidator.ItemValidator items;
        private final ItemFramer framer;
        private final Consumer<ValidationReport> check;
        private boolean finished;

        ValidatingInputStream(InputStream in, OpenApiValidator.ItemValidator items, Consumer<ValidationReport> check) {
            super(in);
            this.items = items;
            this.framer = items.framer();
            this.check = check;
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            var count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            var count = in.read(bytes, offset, length);
            if (count > 0) {
                framer.feed(bytes, offset, count).forEach(this::validate);
            } else if (count < 0 && !finished) {
                finished = true;
                validate(framer.finish());
            }
            return count;
        }

        private void validate(ItemFramer.Frame frame) {
            check.accept(items.validate(frame).report());
        }
    }
}
