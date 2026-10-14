package dev.openapifn.spring.client;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
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
        var content = response.getBody().readAllBytes();
        var status = response.getStatusCode().value();
        var buffered = new Buffered(response, content);
        validation.match().ifPresent(match -> {
            var report = validator.validateResponse(match, new HttpResponse(status, headers(response.getHeaders()),
                content.length == 0 ? Optional.empty() : Optional.of(content)));
            if (report.hasErrors() && failOnInvalidResponse) {
                throw new ClientValidationException("Response of " + request.getMethod() + " " + uri + " violates the API description", report);
            }
            if (!report.isEmpty()) {
                LOG.warn("Response of " + request.getMethod() + " " + uri + " violates the API description:\n" + report);
            }
        });
        return buffered;
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

    /** A response whose body has been read and can be read again. */
    private record Buffered(ClientHttpResponse delegate, byte[] content) implements ClientHttpResponse {

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
            return new ByteArrayInputStream(content);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
