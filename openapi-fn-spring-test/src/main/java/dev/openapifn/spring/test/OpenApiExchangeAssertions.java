package dev.openapifn.spring.test;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.client.ClientValidator;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

/**
 * {@code WebTestClient} assertions that exchanges conform to an API description, the WebFlux
 * counterpart of {@link OpenApiMatchers}.
 *
 * <pre>{@code
 * client.get().uri("/v1/pets").exchange()
 *     .expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(runtimes.get("petstore")));
 * }</pre>
 */
public final class OpenApiExchangeAssertions {

    private OpenApiExchangeAssertions() {
    }

    /** Asserts that both the request and the response conform. */
    public static Consumer<EntityExchangeResult<byte[]>> conformsTo(OpenApiRuntime runtime) {
        return conformsTo(runtime.validator());
    }

    public static Consumer<EntityExchangeResult<byte[]>> conformsTo(OpenApiValidator validator) {
        return result -> check(validator.validate(request(result), response(result)), result);
    }

    /**
     * Asserts that both the request and the response conform to the operation {@code operationId}
     * (path, webhook or callback) without routing, e.g. for webhook and callback receivers.
     */
    public static Consumer<EntityExchangeResult<byte[]>> conformsTo(OpenApiRuntime runtime, String operationId) {
        return result -> {
            var match = new OperationMatch(runtime.operation(operationId), Map.of());
            var validator = runtime.validator();
            check(validator.validateRequest(match, request(result)).report().merge(validator.validateResponse(match, response(result))), result);
        };
    }

    /** Asserts that the response conforms (the request is only used to find the operation). */
    public static Consumer<EntityExchangeResult<byte[]>> responseConformsTo(OpenApiRuntime runtime) {
        return responseConformsTo(runtime.validator());
    }

    public static Consumer<EntityExchangeResult<byte[]>> responseConformsTo(OpenApiValidator validator) {
        return result -> {
            var validation = validator.validateRequest(request(result));
            check(validation.match().map(match -> validator.validateResponse(match, response(result))).orElse(validation.report()), result);
        };
    }

    private static void check(ValidationReport report, EntityExchangeResult<byte[]> result) {
        if (report.hasErrors()) {
            throw new AssertionError(result.getMethod() + " " + result.getUrl() + " does not conform to the API description:\n" + report);
        }
    }

    static HttpRequest request(EntityExchangeResult<byte[]> result) {
        var url = result.getUrl();
        // The validator treats an empty body as none.
        return new HttpRequest(result.getMethod().name(), url.getRawPath(), Optional.ofNullable(url.getRawQuery()).orElse(""),
            headers(result.getRequestHeaders()), Optional.ofNullable(result.getRequestBodyContent()));
    }

    static HttpResponse response(EntityExchangeResult<byte[]> result) {
        return new HttpResponse(result.getStatus().value(), headers(result.getResponseHeaders()), Optional.ofNullable(result.getResponseBodyContent()));
    }

    private static Headers headers(HttpHeaders source) {
        return ClientValidator.headers(source);
    }
}
