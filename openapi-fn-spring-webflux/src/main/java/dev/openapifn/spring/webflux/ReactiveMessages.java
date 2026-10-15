package dev.openapifn.spring.webflux;

import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

/** Conversions from WebFlux messages to openapi-fn's HTTP model. */
public final class ReactiveMessages {

    private ReactiveMessages() {
    }

    /** The path within the application (context path removed), still percent-encoded. */
    public static String path(ServerHttpRequest request) {
        return request.getPath().pathWithinApplication().value();
    }

    /** {@code request} without its body, which is left unread (for streamed bodies). */
    public static HttpRequest head(ServerHttpRequest request) {
        return withBody(request, new byte[0]);
    }

    /** {@code request} with its whole body, read now. */
    public static Mono<HttpRequest> read(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody()).map(ReactiveMessages::bytes).defaultIfEmpty(new byte[0]).map(body -> withBody(request, body));
    }

    /** The content of {@code buffer}, which is released. */
    public static byte[] bytes(DataBuffer buffer) {
        try {
            var bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static HttpRequest withBody(ServerHttpRequest request, byte[] body) {
        return new HttpRequest(request.getMethod().name(), path(request), Optional.ofNullable(request.getURI().getRawQuery()).orElse(""),
            headers(request.getHeaders()), body.length == 0 ? Optional.empty() : Optional.of(body), clientCertificates(request));
    }

    /**
     * The certificates the client presented when the request came over TLS the application
     * terminated; unknown otherwise (TLS ended at a proxy, or there was none).
     */
    static Optional<List<X509Certificate>> clientCertificates(ServerHttpRequest request) {
        var ssl = request.getSslInfo();
        if (ssl == null) {
            return Optional.empty();
        }
        var chain = ssl.getPeerCertificates();
        return Optional.of(chain == null ? List.of() : List.of(chain));
    }

    /** The response as it is about to be written, with {@code body}. */
    public static HttpResponse response(ServerHttpResponse response, byte[] body) {
        var status = response.getStatusCode();
        return new HttpResponse(status == null ? 200 : status.value(), headers(response.getHeaders()), body.length == 0 ? Optional.empty() : Optional.of(body));
    }

    public static Headers headers(HttpHeaders source) {
        var headers = Headers.EMPTY;
        for (var entry : source.headerSet()) {
            for (var value : entry.getValue()) {
                headers = headers.with(entry.getKey(), value);
            }
        }
        return headers;
    }
}
