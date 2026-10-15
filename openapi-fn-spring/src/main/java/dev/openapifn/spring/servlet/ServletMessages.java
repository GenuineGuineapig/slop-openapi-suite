package dev.openapifn.spring.servlet;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/** Conversions from servlet messages to openapi-fn's HTTP model. */
public final class ServletMessages {

    private ServletMessages() {
    }

    /** The path within the application (context path removed), still percent-encoded. */
    public static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * {@code request} including its body: the cached bytes of a {@link CachedBodyRequest} in the
     * wrapper chain, or else the body read now (see {@link CachedBodyRequest#of}).
     */
    public static HttpRequest toHttpRequest(HttpServletRequest request) {
        var cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
        try {
            var source = cached != null ? cached : CachedBodyRequest.of(request);
            return withBody(source, source.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code request} without its body, which is left unread (for streamed bodies). */
    public static HttpRequest head(HttpServletRequest request) {
        return withBody(request, new byte[0]);
    }

    /** The body of {@code request} as a stream. */
    public static InputStream input(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static HttpRequest withBody(HttpServletRequest request, byte[] body) {
        var headers = Headers.EMPTY;
        for (var name : Collections.list(request.getHeaderNames())) {
            for (var value : Collections.list(request.getHeaders(name))) {
                headers = headers.with(name, value);
            }
        }
        return new HttpRequest(request.getMethod(), path(request), Optional.ofNullable(request.getQueryString()).orElse(""), headers,
            body.length == 0 ? Optional.empty() : Optional.of(body), clientCertificates(request));
    }

    /**
     * The certificates the client presented when the request came over TLS the application
     * terminated; unknown otherwise (TLS ended at a proxy, or there was none).
     */
    static Optional<List<X509Certificate>> clientCertificates(HttpServletRequest request) {
        if (!request.isSecure()) {
            return Optional.empty();
        }
        var chain = request.getAttribute("jakarta.servlet.request.X509Certificate") instanceof X509Certificate[] certificates ? certificates
            : new X509Certificate[0];
        return Optional.of(List.of(chain));
    }

    /** The response captured by {@code wrapper}. */
    public static HttpResponse response(ContentCachingResponseWrapper wrapper) {
        var headers = Headers.EMPTY;
        for (var name : wrapper.getHeaderNames()) {
            for (var value : wrapper.getHeaders(name)) {
                headers = headers.with(name, value);
            }
        }
        var contentType = wrapper.getContentType();
        if (contentType != null && !headers.has("Content-Type")) {
            headers = headers.with("Content-Type", contentType);
        }
        var body = wrapper.getContentAsByteArray();
        return new HttpResponse(wrapper.getStatus(), headers, body.length == 0 ? Optional.empty() : Optional.of(body));
    }
}
