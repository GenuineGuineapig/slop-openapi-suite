package dev.openapifn.spring.servlet;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.Multipart;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
     * {@code request} including its body: the cached bytes of a {@link CachedBodyRequest}, the
     * parts of an already parsed multipart request re-encoded (with a new boundary), or the bytes
     * read from the input stream.
     */
    public static HttpRequest toHttpRequest(HttpServletRequest request) {
        var cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
        if (cached != null) {
            return withBody(request, cached.body());
        }
        try {
            if (Optional.ofNullable(request.getContentType()).orElse("").startsWith("multipart/form-data")) {
                var encoded = BodyWriter.multipart(parts(request));
                var message = withBody(request, encoded.body());
                return new HttpRequest(message.method(), message.path(), message.query(),
                    message.headers().without("Content-Type").with("Content-Type", encoded.contentType()), message.body());
            }
            return withBody(request, request.getInputStream().readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ServletException e) {
            throw new UncheckedIOException(new IOException(e));
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
            body.length == 0 ? Optional.empty() : Optional.of(body));
    }

    private static List<Multipart.Part> parts(HttpServletRequest request) throws IOException, ServletException {
        var result = new ArrayList<Multipart.Part>();
        for (var part : request.getParts()) {
            result.add(BodyWriter.part(part.getName(), Optional.ofNullable(part.getSubmittedFileName()), Optional.ofNullable(part.getContentType()),
                part.getInputStream().readAllBytes()));
        }
        return result;
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
