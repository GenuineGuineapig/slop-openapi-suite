package dev.openapifn.spring.client;

import dev.openapifn.http.BodyWriter;
import dev.openapifn.http.ContentType;
import dev.openapifn.http.Headers;
import dev.openapifn.http.ItemFramer;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encodes {@link ClientCall}s for sending, whatever the HTTP client: the URI (with serialized path
 * and query parameters), the headers (header and cookie parameters, {@code Accept}) and the body.
 */
public final class ClientCodec {

    private static final Pattern EXPRESSION = Pattern.compile("\\{([^}]+)}");
    private static final BodyWriter WRITER = new BodyWriter(new SchemaShapes(SchemaRegistry.empty()));

    private final JsonMapper mapper;

    public ClientCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** The absolute URI of {@code call}: base URL, serialized path parameters and query. */
    public URI uri(String baseUrl, ClientCall call) {
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

    /** The headers of {@code call}: header parameters, cookie parameters as one {@code Cookie} header, and {@code Accept}. */
    public Headers headers(ClientCall call) {
        var headers = Headers.EMPTY;
        var cookies = new ArrayList<Map.Entry<String, String>>();
        for (var param : call.parameters()) {
            switch (param.spec().in()) {
                case HEADER -> headers = headers.with(param.spec().name(), ParameterSerializer.header(param.spec(), toJson(param.value())));
                case COOKIE -> cookies.addAll(ParameterSerializer.cookie(param.spec(), toJson(param.value())));
                default -> {
                }
            }
        }
        if (!cookies.isEmpty()) {
            headers = headers.with("Cookie", ParameterSerializer.cookieHeader(cookies));
        }
        if (!call.acceptTypes().isEmpty()) {
            headers = headers.with("Accept", String.join(", ", call.acceptTypes()));
        }
        return headers;
    }

    /**
     * The encoded body and its content type. Multipart bodies get a boundary: lists of parts become
     * {@code multipart/form-data}, other lists positional parts, and other values are encoded from
     * their JSON. Files are sent with their own content type when the declared one is a range or
     * a multipart type (whose boundary the file content carries). Streams are collected.
     */
    public BodyWriter.MultipartBody encode(ClientCall.Body body) {
        var type = contentType(body);
        var multipart = type.type().equals("multipart");
        return switch (body.value()) {
            case Stream<?> items -> {
                try (items) {
                    yield encode(new ClientCall.Body(body.contentType(), items.toList(), body.encodings()));
                }
            }
            case FilePart file -> new BodyWriter.MultipartBody(file.content(), multipart || body.contentType().contains("*") ? file.contentType()
                : body.contentType());
            case byte[] bytes -> new BodyWriter.MultipartBody(bytes, body.contentType());
            case List<?> parts when type.essence().equals("multipart/form-data") -> BodyWriter.multipart(parts.stream()
                .flatMap(part -> parts((ClientCall.Part) part).stream()).toList());
            case List<?> items when multipart -> BodyWriter.multipart(type.subtype(), items.stream().map(this::item).toList());
            case Object value when multipart -> {
                var bounded = body.contentType() + "; boundary=" + BodyWriter.boundary();
                yield new BodyWriter.MultipartBody(WRITER.write(toJson(value), ContentType.parse(bounded).orElseThrow(), Optional.empty()), bounded);
            }
            case Object value when type.essence().equals("application/x-www-form-urlencoded") -> new BodyWriter.MultipartBody(
                BodyWriter.form(toJson(value), body.encodings()).getBytes(StandardCharsets.UTF_8), body.contentType());
            default -> new BodyWriter.MultipartBody(type.isJson() ? mapper.writeValueAsBytes(body.value())
                : WRITER.write(toJson(body.value()), type, Optional.empty()), body.contentType());
        };
    }

    /**
     * The content type to send a body of items with, if {@code body} is declared as a sequential
     * media type (a multipart type gets a boundary): its items are then encoded one by one with
     * {@link #encodeItem} as they are produced.
     */
    public Optional<ContentType> itemType(ClientCall.Body body) {
        var declared = contentType(body);
        var type = declared.type().equals("multipart") && declared.parameter("boundary").isEmpty()
            ? ContentType.parse(body.contentType() + "; boundary=" + BodyWriter.boundary()).orElseThrow() : declared;
        return Optional.of(type).filter(ItemFramer::isSequential);
    }

    /** One item of a body of {@code type} (see {@link #itemType}); files become positional parts as they are. */
    public byte[] encodeItem(Object value, ContentType type) {
        return type.type().equals("multipart") ? BodyWriter.part(type.parameter("boundary").orElseThrow(), item(value))
            : BodyWriter.item(toJson(value), type);
    }

    private static ContentType contentType(ClientCall.Body body) {
        return ContentType.parse(body.contentType()).orElseThrow(() -> new IllegalArgumentException("Invalid content type " + body.contentType()));
    }

    private List<Multipart.Part> parts(ClientCall.Part part) {
        List<?> values = part.value() instanceof Collection<?> collection ? new ArrayList<>(collection) : List.of(part.value());
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

    /** A positional part (e.g. of {@code multipart/mixed}): files as they are, other values as JSON or text. */
    private Multipart.Part item(Object value) {
        return value instanceof FilePart file ? BodyWriter.part(file.contentType(), file.content()) : BodyWriter.part(Optional.empty(), toJson(value));
    }

    private JsonValue toJson(Object value) {
        return JacksonJson.toJsonValue(mapper.valueToTree(value));
    }
}
