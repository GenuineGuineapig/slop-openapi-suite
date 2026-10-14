package dev.openapifn.spring.client;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.StreamedBody;
import java.net.URI;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
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

    public OpenApiHttpClient(RestClient restClient, String baseUrl, JsonMapper mapper) {
        this.restClient = restClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
        this.codec = new ClientCodec(mapper);
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** A client sending to {@code baseUrl} instead, e.g. the URL a webhook subscriber or callback registered. */
    public OpenApiHttpClient to(String baseUrl) {
        return new OpenApiHttpClient(restClient, baseUrl, mapper);
    }

    /** Sends {@code call} and decodes the response. */
    public <R> R exchange(ClientCall call, Function<ClientResponse, R> decoder) {
        var request = restClient.method(HttpMethod.valueOf(call.method())).uri(uri(call));
        if (call.operationId() != null) {
            request.attribute(OPERATION_ATTRIBUTE, call.operationId());
        }
        codec.headers(call).values().forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        var body = call.body();
        if (body != null) {
            var itemType = body.value() instanceof Stream<?> ? codec.itemType(body) : Optional.<ContentType>empty();
            if (itemType.isPresent()) {
                var type = itemType.get();
                var streamed = new StreamedBody((Stream<?>) body.value(), item -> codec.encodeItem(item, type), BodyWriter.end(type));
                request.header("Content-Type", type.toString()).body(streamed::writeTo);
            } else {
                var encoded = encode(body);
                request.header("Content-Type", encoded.contentType()).body(encoded.body());
            }
        }
        return request.exchange((clientRequest, response) -> {
            var headers = Headers.EMPTY;
            for (var entry : response.getHeaders().headerSet()) {
                for (var value : entry.getValue()) {
                    headers = headers.with(entry.getKey(), value);
                }
            }
            var status = response.getStatusCode().value();
            if (!ItemStreams.sequential(headers.first("Content-Type").orElse(null))) {
                try (response) {
                    return decoder.apply(new ClientResponse(status, headers, response.getBody().readAllBytes(), mapper));
                }
            }
            var lazy = new ClientResponse(status, headers, response.getBody(), response::close, mapper);
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

    /** The absolute URI of {@code call}, see {@link ClientCodec#uri}. */
    URI uri(ClientCall call) {
        return codec.uri(baseUrl, call);
    }

    /** The encoded body, see {@link ClientCodec#encode}. */
    BodyWriter.MultipartBody encode(ClientCall.Body body) {
        return codec.encode(body);
    }
}
