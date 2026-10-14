package dev.openapifn.spring.client;

import dev.openapifn.spring.ItemStreams;
import dev.openapifn.validation.OpenApiValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Validates the requests a {@code RestClient} sends and the responses it receives against an API
 * description, for {@code RestClient}s other than those of generated clients (which validate
 * themselves, see {@link OpenApiHttpClient}). Request violations prevent sending; response
 * violations are thrown or logged. Response bodies of sequential media types are validated item by
 * item while they are read, so they still stream. Spring buffers request bodies for interceptors,
 * so a streamed request body is validated whole.
 */
public final class OpenApiClientValidationInterceptor implements ClientHttpRequestInterceptor {

    private final ClientValidator validator;

    public OpenApiClientValidationInterceptor(OpenApiValidator validator, boolean failOnInvalidResponse) {
        this.validator = new ClientValidator(validator, failOnInvalidResponse);
    }

    @Override
    public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
        throws IOException {
        var operationId = request.getAttributes().get(OpenApiHttpClient.OPERATION_ATTRIBUTE);
        var match = validator.request(request.getMethod().name(), request.getURI(), ClientValidator.headers(request.getHeaders()), body, false,
            operationId == null ? null : operationId.toString());
        var response = execution.execute(request, body);
        var status = response.getStatusCode().value();
        var headers = ClientValidator.headers(response.getHeaders());
        var description = request.getMethod() + " " + request.getURI();
        if (ItemStreams.sequential(headers.first("Content-Type").orElse(null))) {
            var streamed = validator.streamedResponse(match, status, headers, response.getBody(), description);
            return new Replaced(response, () -> streamed);
        }
        var content = response.getBody().readAllBytes();
        validator.response(match, status, headers, content, description);
        return new Replaced(response, () -> new ByteArrayInputStream(content));
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
