package dev.openapifn.spring.client;

import dev.openapifn.spring.ItemStreams;
import dev.openapifn.validation.OpenApiValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Validates the requests a {@code RestClient} sends and the responses it receives against an API
 * description, for {@code RestClient}s other than those of generated clients (which validate
 * themselves, see {@link OpenApiHttpClient}). Request violations prevent sending; response
 * violations are thrown or logged. Response bodies of sequential media types are validated item by
 * item while they are read, so they still stream. Spring buffers request bodies for interceptors;
 * {@link OpenApiValidatingRequestFactory} validates streamed request bodies as they are sent.
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
            return new ReplacedResponse(response, () -> streamed);
        }
        var content = response.getBody().readAllBytes();
        validator.response(match, status, headers, content, description);
        return new ReplacedResponse(response, () -> new ByteArrayInputStream(content));
    }
}
