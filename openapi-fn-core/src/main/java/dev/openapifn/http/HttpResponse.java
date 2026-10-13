package dev.openapifn.http;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * A framework-neutral HTTP response.
 *
 * @param status status code
 * @param headers response headers
 * @param body the response body, if any
 */
public record HttpResponse(int status, Headers headers, Optional<byte[]> body) {

    public HttpResponse {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    public static HttpResponse of(int status) {
        return new HttpResponse(status, Headers.EMPTY, Optional.empty());
    }

    public HttpResponse withHeader(String name, String value) {
        return new HttpResponse(status, headers.with(name, value), body);
    }

    public HttpResponse withBody(byte[] content) {
        return new HttpResponse(status, headers, Optional.of(content));
    }

    /** A copy with a UTF-8 body and the given content type. */
    public HttpResponse withBody(String contentType, String content) {
        return withHeader("Content-Type", contentType).withBody(content.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<String> contentType() {
        return headers.first("Content-Type");
    }
}
