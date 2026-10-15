package dev.openapifn.http;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A framework-neutral HTTP request.
 *
 * @param method upper-case HTTP method
 * @param path the raw (percent-encoded) request path, without query
 * @param query the raw query string without {@code ?}, or empty
 * @param headers request headers
 * @param body the request body, if any
 * @param clientCertificates the certificate chain the client presented in the TLS handshake (empty
 *     when it presented none); unknown (empty) when TLS was not terminated by the application, as
 *     behind a proxy
 */
public record HttpRequest(String method, String path, String query, Headers headers, Optional<byte[]> body,
                          Optional<List<X509Certificate>> clientCertificates) {

    public HttpRequest {
        method = method.toUpperCase(Locale.ROOT);
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        clientCertificates = clientCertificates.map(List::copyOf);
    }

    /** A request whose TLS client certificates are unknown. */
    public HttpRequest(String method, String path, String query, Headers headers, Optional<byte[]> body) {
        this(method, path, query, headers, body, Optional.empty());
    }

    /** A request for {@code target}, which may contain a query string ({@code /pets?limit=5}). */
    public static HttpRequest of(String method, String target) {
        var q = target.indexOf('?');
        return new HttpRequest(method, q < 0 ? target : target.substring(0, q), q < 0 ? "" : target.substring(q + 1), Headers.EMPTY,
            Optional.empty());
    }

    public HttpRequest withHeader(String name, String value) {
        return new HttpRequest(method, path, query, headers.with(name, value), body, clientCertificates);
    }

    public HttpRequest withBody(byte[] content) {
        return new HttpRequest(method, path, query, headers, Optional.of(content), clientCertificates);
    }

    /** A copy received over TLS, where the client presented {@code chain} (empty for none). */
    public HttpRequest withClientCertificates(List<X509Certificate> chain) {
        return new HttpRequest(method, path, query, headers, body, Optional.of(chain));
    }

    /** A copy with a UTF-8 body and the given content type. */
    public HttpRequest withBody(String contentType, String content) {
        return withHeader("Content-Type", contentType).withBody(content.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<String> contentType() {
        return headers.first("Content-Type");
    }
}
