package dev.openapifn.spring.test;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.servlet.CachedBodyRequest;
import dev.openapifn.spring.servlet.ServletMessages;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * MockMvc matchers asserting that exchanges conform to an API description.
 *
 * <pre>{@code
 * mvc.perform(get("/v1/pets")).andExpect(OpenApiMatchers.conformsTo(runtimes.get("petstore")));
 * }</pre>
 */
public final class OpenApiMatchers {

    private OpenApiMatchers() {
    }

    /** Asserts that both the request and the response conform. */
    public static ResultMatcher conformsTo(OpenApiRuntime runtime) {
        return conformsTo(runtime.validator());
    }

    public static ResultMatcher conformsTo(OpenApiValidator validator) {
        return result -> check(validator.validate(request(result), response(result)), result);
    }

    /**
     * Asserts that both the request and the response conform to the operation {@code operationId}
     * (path, webhook or callback) without routing, e.g. for webhook and callback receivers.
     */
    public static ResultMatcher conformsTo(OpenApiRuntime runtime, String operationId) {
        return result -> {
            var match = new OperationMatch(runtime.operation(operationId), Map.of());
            var validator = runtime.validator();
            check(validator.validateRequest(match, request(result)).report().merge(validator.validateResponse(match, response(result))), result);
        };
    }

    /** Asserts that the response conforms (the request is only used to find the operation). */
    public static ResultMatcher responseConformsTo(OpenApiRuntime runtime) {
        return responseConformsTo(runtime.validator());
    }

    public static ResultMatcher responseConformsTo(OpenApiValidator validator) {
        return result -> {
            var validation = validator.validateRequest(request(result));
            var report = validation.match().map(match -> validator.validateResponse(match, response(result))).orElse(validation.report());
            check(report, result);
        };
    }

    private static void check(ValidationReport report, MvcResult result) {
        if (report.hasErrors()) {
            throw new AssertionError(result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()
                + " does not conform to the API description:\n" + report);
        }
    }

    /** The request of {@code result}, reading its content (or its multipart parts) again. */
    static HttpRequest request(MvcResult result) throws IOException {
        MockHttpServletRequest request = result.getRequest();
        var content = request.getContentAsByteArray();
        var cached = content != null ? new CachedBodyRequest(request, content) : CachedBodyRequest.of(request);
        return ServletMessages.toHttpRequest(cached);
    }

    static HttpResponse response(MvcResult result) {
        MockHttpServletResponse response = result.getResponse();
        var headers = Headers.EMPTY;
        for (var name : response.getHeaderNames()) {
            for (var value : response.getHeaders(name)) {
                headers = headers.with(name, value);
            }
        }
        var body = response.getContentAsByteArray();
        return new HttpResponse(response.getStatus(), headers, body.length == 0 ? Optional.empty() : Optional.of(body));
    }
}
