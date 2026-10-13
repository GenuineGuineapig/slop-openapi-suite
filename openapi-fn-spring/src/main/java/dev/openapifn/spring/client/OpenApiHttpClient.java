package dev.openapifn.spring.client;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.Multipart;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.http.SchemaShapes;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.JacksonJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern EXPRESSION = Pattern.compile("\\{([^}]+)}");
    private static final BodyWriter WRITER = new BodyWriter(new SchemaShapes(SchemaRegistry.empty()));

    private final RestClient restClient;
    private final String baseUrl;
    private final JsonMapper mapper;

    public OpenApiHttpClient(RestClient restClient, String baseUrl, JsonMapper mapper) {
        this.restClient = restClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.mapper = mapper;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Sends {@code call} and decodes the response. */
    public <R> R exchange(ClientCall call, Function<ClientResponse, R> decoder) {
        var request = restClient.method(HttpMethod.valueOf(call.method())).uri(uri(call));
        var cookies = new ArrayList<Map.Entry<String, String>>();
        for (var param : call.parameters()) {
            var value = toJson(param.value());
            switch (param.spec().in()) {
                case HEADER -> request.header(param.spec().name(), ParameterSerializer.header(param.spec(), value));
                case COOKIE -> cookies.addAll(ParameterSerializer.cookie(param.spec(), value));
                default -> {
                }
            }
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", ParameterSerializer.cookieHeader(cookies));
        }
        if (!call.acceptTypes().isEmpty()) {
            request.header("Accept", String.join(", ", call.acceptTypes()));
        }
        var body = call.body();
        if (body != null) {
            var encoded = encode(body);
            request.header("Content-Type", encoded.contentType()).body(encoded.body());
        }
        return request.exchange((clientRequest, response) -> {
            var headers = Headers.EMPTY;
            for (var entry : response.getHeaders().headerSet()) {
                for (var value : entry.getValue()) {
                    headers = headers.with(entry.getKey(), value);
                }
            }
            var content = response.getBody().readAllBytes();
            return decoder.apply(new ClientResponse(response.getStatusCode().value(), headers, content, mapper));
        });
    }

    /** The absolute URI of {@code call}: base URL, serialized path parameters and query. */
    URI uri(ClientCall call) {
        var path = new StringBuilder();
        Matcher matcher = EXPRESSION.matcher(call.pathTemplate());
        int last = 0;
        while (matcher.find()) {
            path.append(call.pathTemplate(), last, matcher.start());
            var name = matcher.group(1);
            var param = call.parameters().stream().filter(p -> p.spec().in() == ParameterLocation.PATH && p.spec().name().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing path parameter '" + name + "'"));
            path.append(ParameterSerializer.path(param.spec(), toJson(param.value())));
            last = matcher.end();
        }
        path.append(call.pathTemplate().substring(last));
        var query = new ArrayList<Map.Entry<String, String>>();
        String querystring = null;
        for (var param : call.parameters()) {
            switch (param.spec().in()) {
                case QUERY -> query.addAll(ParameterSerializer.query(param.spec(), toJson(param.value())));
                case QUERYSTRING -> querystring = param.value() instanceof String text ? text : BodyWriter.form(toJson(param.value()));
                default -> {
                }
            }
        }
        var queryText = querystring != null ? querystring : ParameterSerializer.queryString(query);
        return URI.create(baseUrl + path + (queryText.isEmpty() ? "" : "?" + queryText));
    }

    /** The encoded body and its content type (multipart bodies get a boundary). */
    BodyWriter.MultipartBody encode(ClientCall.Body body) {
        var type = ContentType.parse(body.contentType()).orElseThrow(() -> new IllegalArgumentException("Invalid content type " + body.contentType()));
        return switch (body.value()) {
            case FilePart file -> new BodyWriter.MultipartBody(file.content(), body.contentType());
            case byte[] bytes -> new BodyWriter.MultipartBody(bytes, body.contentType());
            case List<?> parts when type.essence().equals("multipart/form-data") -> BodyWriter.multipart(parts.stream()
                .flatMap(part -> parts((ClientCall.Part) part).stream()).toList());
            default -> new BodyWriter.MultipartBody(type.isJson() ? mapper.writeValueAsBytes(body.value())
                : WRITER.write(toJson(body.value()), type, Optional.empty()), body.contentType());
        };
    }

    private List<Multipart.Part> parts(ClientCall.Part part) {
        var values = part.value() instanceof List<?> list ? list : List.of(part.value());
        var result = new ArrayList<Multipart.Part>();
        for (var value : values) {
            if (value instanceof FilePart file) {
                result.add(BodyWriter.part(part.name(), Optional.ofNullable(file.filename()), Optional.of(file.contentType()), file.content()));
            } else {
                var json = part.contentType() != null && ContentType.parse(part.contentType()).filter(ContentType::isJson).isPresent();
                var bytes = json ? mapper.writeValueAsBytes(value) : ParameterSerializer.text(toJson(value)).getBytes(StandardCharsets.UTF_8);
                result.add(BodyWriter.part(part.name(), Optional.empty(), Optional.ofNullable(part.contentType()), bytes));
            }
        }
        return result;
    }

    private JsonValue toJson(Object value) {
        return JacksonJson.toJsonValue(mapper.valueToTree(value));
    }
}
