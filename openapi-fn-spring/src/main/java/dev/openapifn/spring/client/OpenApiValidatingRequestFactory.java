package dev.openapifn.spring.client;

import dev.openapifn.http.ContentType;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Validates the requests a {@code RestClient} (or {@code RestTemplate}) sends and the responses it
 * receives against an API description, without buffering what streams: wrap the client's request
 * factory with it ({@code RestClient.builder().requestFactory(new OpenApiValidatingRequestFactory(...))}).
 *
 * <p>A request body of a sequential media type is passed on item by item as it is written, each
 * item once it is complete and valid, and the sequence is checked as a whole when the request is
 * executed; other bodies are validated whole before anything is sent (a JSON document cannot be
 * judged before its end). Response bodies of sequential media types are validated item by item as
 * they are read; others whole. Request violations are {@link ClientValidationException}s; response
 * violations are thrown or logged. Unlike {@link OpenApiClientValidationInterceptor}, which Spring
 * hands buffered bodies, this works for streamed requests too.
 */
public final class OpenApiValidatingRequestFactory implements ClientHttpRequestFactory {

    private final ClientHttpRequestFactory delegate;
    private final ClientValidator validator;

    public OpenApiValidatingRequestFactory(ClientHttpRequestFactory delegate, OpenApiValidator validator, boolean failOnInvalidResponse) {
        this.delegate = delegate;
        this.validator = new ClientValidator(validator, failOnInvalidResponse);
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        return new ValidatingRequest(delegate.createRequest(uri, httpMethod), validator);
    }

    /**
     * A request validated as its body is written and when it is executed. Spring's clients hand it
     * the body to write ({@link StreamingHttpOutputMessage}), which it passes on to a streaming
     * request as it is written; others write to {@link #getBody()}.
     */
    private static final class ValidatingRequest implements ClientHttpRequest, StreamingHttpOutputMessage {

        private final ClientHttpRequest delegate;
        private final ClientValidator validator;
        private StreamingHttpOutputMessage.@Nullable Body body;
        private @Nullable ByteArrayOutputStream buffered;
        private ClientValidator.@Nullable ItemSink sink;
        private Optional<OperationMatch> match = Optional.empty();

        ValidatingRequest(ClientHttpRequest delegate, ClientValidator validator) {
            this.delegate = delegate;
            this.validator = validator;
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public void setBody(StreamingHttpOutputMessage.Body body) {
            this.body = body;
        }

        /** The headers are final now: a streamed body is passed on item by item, another one buffered until it is complete. */
        @Override
        public OutputStream getBody() throws IOException {
            if (sink == null && buffered == null) {
                var type = sequentialType();
                if (type.isPresent()) {
                    match = request(null, true);
                    sink = validator.requestItems(match, type.get(), delegate.getBody());
                } else {
                    buffered = new ByteArrayOutputStream();
                }
            }
            return sink != null ? sink : Objects.requireNonNull(buffered);
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            var source = body;
            if (source != null) {
                var type = sequentialType();
                if (type.isPresent()) {
                    send(out -> {
                        match = request(null, true);
                        var items = validator.requestItems(match, type.get(), out);
                        source.writeTo(items);
                        items.finish();
                    });
                } else {
                    var content = new ByteArrayOutputStream();
                    source.writeTo(content);
                    var bytes = content.toByteArray();
                    match = request(bytes, false);
                    send(out -> out.write(bytes));
                }
            } else if (sink != null) {
                sink.finish();
            } else {
                var bytes = buffered != null ? buffered.toByteArray() : null;
                match = request(bytes, false);
                if (bytes != null) {
                    delegate.getBody().write(bytes);
                }
            }
            var response = delegate.execute();
            var status = response.getStatusCode().value();
            var headers = ClientValidator.headers(response.getHeaders());
            var description = getMethod() + " " + getURI();
            if (ItemStreams.sequential(headers.first("Content-Type").orElse(null))) {
                var streamed = validator.streamedResponse(match, status, headers, response.getBody(), description);
            return new ReplacedResponse(response, () -> streamed);
            }
            var content = response.getBody().readAllBytes();
            validator.response(match, status, headers, content, description);
            return new ReplacedResponse(response, () -> new ByteArrayInputStream(content));
        }

        /** The content type of a body of a sequential media type. */
        private Optional<ContentType> sequentialType() {
            var contentType = delegate.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            return ItemStreams.sequential(contentType) ? ContentType.parse(contentType) : Optional.empty();
        }

        /** Hands {@code content} to the request to write: as it streams, if it can. */
        private void send(StreamingHttpOutputMessage.Body content) throws IOException {
            if (delegate instanceof StreamingHttpOutputMessage streaming) {
                streaming.setBody(content);
            } else {
                content.writeTo(delegate.getBody());
            }
        }

        private Optional<OperationMatch> request(byte @Nullable [] bytes, boolean head) {
            var operationId = getAttributes().get(OpenApiHttpClient.OPERATION_ATTRIBUTE);
            return validator.request(getMethod().name(), getURI(), ClientValidator.headers(getHeaders()), bytes, head,
                operationId == null ? null : operationId.toString());
        }
    }
}
