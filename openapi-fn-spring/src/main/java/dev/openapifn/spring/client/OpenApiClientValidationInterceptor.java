package dev.openapifn.spring.client;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Validates the requests a {@code RestClient} sends and the responses it receives against an API
 * description. Request violations prevent sending; response violations are thrown or logged.
 * Response bodies of sequential media types are validated item by item while they are read, so
 * they still stream: a violation is thrown (or logged) when the item is read. (Request bodies are
 * buffered by {@code RestClient} for interceptors, so streamed request bodies are validated whole.)
 */
public final class OpenApiClientValidationInterceptor implements ClientHttpRequestInterceptor {

    private static final Log LOG = LogFactory.getLog(OpenApiClientValidationInterceptor.class);

    private final OpenApiValidator validator;
    private final boolean failOnInvalidResponse;

    public OpenApiClientValidationInterceptor(OpenApiValidator validator, boolean failOnInvalidResponse) {
        this.validator = validator;
        this.failOnInvalidResponse = failOnInvalidResponse;
    }

    @Override
    public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        var uri = request.getURI();
        var message = new HttpRequest(request.getMethod().name(), uri.getRawPath(), Optional.ofNullable(uri.getRawQuery()).orElse(""),
            headers(request.getHeaders()), body.length == 0 ? Optional.empty() : Optional.of(body));
        var validation = operation(request).map(operation -> validator.validateRequest(new OperationMatch(operation, Map.of()), message))
            .orElseGet(() -> validator.validateRequest(message));
        if (validation.report().hasErrors()) {
            throw new ClientValidationException("Request " + request.getMethod() + " " + uri + " violates the API description", validation.report());
        }
        var response = execution.execute(request, body);
        var status = response.getStatusCode().value();
        var match = validation.match();
        var contentType = response.getHeaders().getFirst("Content-Type");
        var items = match.flatMap(m -> contentType == null ? Optional.empty() : validator.responseItems(m, status, contentType));
        if (items.isPresent()) {
            check(request, validator.validateResponseHead(match.get(), new HttpResponse(status, headers(response.getHeaders()), Optional.empty())));
            var validating = new ValidatingInputStream(response.getBody(), items.get(), report -> check(request, report));
            return new Replaced(response, () -> validating);
        }
        var content = response.getBody().readAllBytes();
        match.ifPresent(m -> check(request, validator.validateResponse(m, new HttpResponse(status, headers(response.getHeaders()),
            content.length == 0 ? Optional.empty() : Optional.of(content)))));
        return new Replaced(response, () -> new ByteArrayInputStream(content));
    }

    /** Throws or logs the violations of a response (or of an item of it). */
    private void check(org.springframework.http.HttpRequest request, ValidationReport report) {
        if (report.hasErrors() && failOnInvalidResponse) {
            throw new ClientValidationException("Response of " + request.getMethod() + " " + request.getURI() + " violates the API description", report);
        }
        if (!report.isEmpty()) {
            LOG.warn("Response of " + request.getMethod() + " " + request.getURI() + " violates the API description:\n" + report);
        }
    }

    /** The webhook or callback a request belongs to: those have no path to route by. */
    private Optional<ApiOperation> operation(org.springframework.http.HttpRequest request) {
        return Optional.ofNullable(request.getAttributes().get(OpenApiHttpClient.OPERATION_ATTRIBUTE))
            .flatMap(id -> validator.api().operation(id.toString()))
            .filter(operation -> operation.kind() != ApiOperation.Kind.PATH);
    }

    private static Headers headers(HttpHeaders source) {
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

    /** A response whose body is replaced: by its buffered content, or by a validating stream. */
    private record Replaced(ClientHttpResponse delegate, Supplier<InputStream> body) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return body.get();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
