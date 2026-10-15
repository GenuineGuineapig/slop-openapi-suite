package dev.openapifn.spring.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/** A response whose body is replaced: by its buffered content (read anew each time), or by a validating stream. */
record ReplacedResponse(ClientHttpResponse delegate, Supplier<InputStream> body) implements ClientHttpResponse {

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
