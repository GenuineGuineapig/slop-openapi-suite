package dev.openapifn.spring.client;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.StreamedBody;
import dev.openapifn.validation.OperationMatch;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends {@link ClientCall}s with a {@link RestClient} and hands the {@link ClientResponse} to a
 * decoder. Parameters are serialized according to their specs; the URI is built here (fully
 * encoded) so the {@code RestClient}'s own base URL and URI encoding do not interfere. Every
 * status is passed to the decoder: described error responses are results, not exceptions.
 */
public final class OpenApiHttpClient {

    /** Request attribute naming the operation of a call (see {@link ClientCall#operationId()}). */
    public static final String OPERATION_ATTRIBUTE = OpenApiHttpClient.class.getName() + ".operation";

    private final RestClient restClient;
    private final String baseUrl;
    private final JsonMapper mapper;
    private final ClientCodec codec;
    private final @Nullable ClientValidator validator;

    public OpenApiHttpClient(RestClient restClient, String baseUrl, JsonMapper mapper) {
        this(restClient, baseUrl, mapper, null);
    }

    /**
     * @param validator validates requests as they are sent (with the client's default headers, a
     *     streamed body item by item as it is written) and responses as they are read, if given
     */
    public OpenApiHttpClient(RestClient restClient, String baseUrl, JsonMapper mapper, @Nullable ClientValidator validator) {
        this.restClient = restClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.codec = new ClientCodec(mapper);
        this.validator = validator;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** A client sending to {@code baseUrl} instead, e.g. the URL a webhook subscriber or callback registered. */
    public OpenApiHttpClient to(String baseUrl) {
        return new OpenApiHttpClient(restClient, baseUrl, mapper, validator);
    }

    /** Sends {@code call} and decodes the response. */
    public <R> R exchange(ClientCall call, Function<ClientResponse, R> decoder) {
        var uri = uri(call);
        var request = restClient.method(HttpMethod.valueOf(call.method())).uri(uri);
        if (call.operationId() != null) {
            request.attribute(OPERATION_ATTRIBUTE, call.operationId());
        }
        codec.headers(call).values().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        var match = new AtomicReference<Optional<OperationMatch>>(Optional.empty());
        var body = call.body();
        byte[] sent = null;
        var streamed = false;
        if (body != null) {
            var itemType = body.value() instanceof Stream<?> ? codec.itemType(body) : Optional.<ContentType>empty();
            if (itemType.isPresent()) {
                var type = itemType.get();
                var check = new AtomicReference<@Nullable Consumer<byte[]>>();
                var items = new StreamedBody((Stream<?>) body.value(), item -> {
                    var bytes = codec.encodeItem(item, type);
                    if (validator != null) {
                        check.updateAndGet(current -> current != null ? current : validator.requestItems(match.get(), type)).accept(bytes);
                    }
                    return bytes;
                }, BodyWriter.end(type));
                request.header("Content-Type", type.toString()).body(items::writeTo);
                streamed = true;
            } else {
                var encoded = encode(body);
                request.header("Content-Type", encoded.contentType()).body(encoded.body());
                sent = encoded.body();
            }
        }
        if (validator != null) {
            // Validated as it is about to be sent: with the client's default headers, before any byte of it is transmitted.
            var content = sent;
            var head = streamed;
            request.httpRequest(clientRequest -> match.set(validator.request(clientRequest.getMethod().name(), clientRequest.getURI(),
                ClientValidator.headers(clientRequest.getHeaders()), content, head, call.operationId())));
        }
        var description = call.method() + " " + uri;
        return request.exchange((clientRequest, response) -> {
            var headers = ClientValidator.headers(response.getHeaders());
            var status = response.getStatusCode().value();
            if (!ItemStreams.sequential(headers.first("Content-Type").orElse(null))) {
                try (response) {
                    var content = response.getBody().readAllBytes();
                    if (validator != null) {
                        validator.response(match.get(), status, headers, content, description);
                    }
                    return decoder.apply(new ClientResponse(status, headers, content, mapper));
                }
            }
            var input = validator == null ? response.getBody() : validator.streamedResponse(match.get(), status, headers, response.getBody(), description);
            var lazy = new ClientResponse(status, headers, input, response::close, mapper);
            try {
                var result = decoder.apply(lazy);
                lazy.release();
                return result;
            } catch (RuntimeException e) {
                response.close();
                throw e;
            }
        }, false);
    }

    /**
     * What runtime expressions select from {@code call}, sent by this client, and the response it
     * got (its status, body and header values as name/value pairs): the values the Link Objects of
     * that response give the request of the operation they lead to.
     */
    public ExchangeValues values(ClientCall call, int status, @Nullable Object body, @Nullable Object... headers) {
        return codec.values(baseUrl, call, status, body, headers);
    }

    /** What runtime expressions select from {@code call} alone, such as the URLs of its callbacks. */
    public ExchangeValues values(ClientCall call) {
        return codec.values(baseUrl, call, null, null);
    }

    /** The absolute URI of {@code call}, see {@link ClientCodec#uri}. */
    URI uri(ClientCall call) {
        return codec.uri(baseUrl, call);
    }

    /** The encoded body, see {@link ClientCodec#encode}. */
    BodyWriter.MultipartBody encode(ClientCall.Body body) {
        return codec.encode(body);
    }
}
