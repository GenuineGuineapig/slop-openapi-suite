package dev.openapifn.validation;

import dev.openapifn.generator.example.ExampleGenerator;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.schema.Direction;
import dev.openapifn.schema.Schema;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Requests and responses built from descriptions alone, for comparing openapi-fn's validation with
 * other validators (see {@link AtlassianDifferentialTest} and {@link NetworkntDifferentialTest}).
 */
final class Interactions {

    static final Path PROJECT = project();

    private Interactions() {
    }

    /** The repository: the directory with the build's {@code .mvn}, above these classes. */
    private static Path project() {
        try {
            var classes = Path.of(Interactions.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Stream.iterate(classes, dir -> dir != null, Path::getParent).filter(dir -> Files.isDirectory(dir.resolve(".mvn"))).findFirst()
                .orElseThrow();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A request, as validators get it. */
    record Message(String method, String path, List<Map.Entry<String, String>> query, Map<String, String> headers, Optional<String> body) {

        Message with(List<Map.Entry<String, String>> otherQuery, Map<String, String> otherHeaders, Optional<String> otherBody) {
            return new Message(method, path, otherQuery, otherHeaders, otherBody);
        }

        Message withBody(JsonValue otherBody) {
            return with(query, headers, Optional.of(JsonWriter.compact(otherBody)));
        }

        HttpRequest ours() {
            var request = HttpRequest.of(method, path + (query.isEmpty() ? "" : "?" + ParameterSerializer.queryString(query)));
            for (var header : headers.entrySet()) {
                request = request.withHeader(header.getKey(), header.getValue());
            }
            var head = request;
            return body.map(text -> head.withBody(text.getBytes(StandardCharsets.UTF_8))).orElse(head);
        }
    }

    /** The path of the first server's URL (a relative URL's taken as absolute), without a trailing slash. */
    static String serverPath(OpenApiDocument api) {
        return api.model().servers().getFirst().defaultUrl().replaceFirst("^[a-z]+://[^/]*", "").replaceFirst("^(?=[^/])", "/").replaceFirst("/$", "");
    }

    /** A valid request: example values for the path and the required parameters, credentials, and a JSON body if one is described. */
    static Optional<Message> valid(OpenApiDocument api, ApiOperation operation, ExampleGenerator examples) {
        var path = operation.path();
        var query = new ArrayList<Map.Entry<String, String>>();
        var headers = new LinkedHashMap<String, String>();
        for (var parameter : operation.parameters()) {
            if (!parameter.required() && parameter.in() != ParameterLocation.PATH) {
                continue;
            }
            var value = example(parameter, examples);
            if (value.isEmpty() || !parameter.content().isEmpty()) {
                return Optional.empty();
            }
            var spec = ParameterSerializer.Spec.of(parameter);
            switch (parameter.in()) {
                case PATH -> path = path.replace("{" + parameter.name() + "}", ParameterSerializer.path(spec, value.get()));
                case QUERY -> query.addAll(ParameterSerializer.query(spec, value.get()));
                case HEADER -> headers.put(parameter.name(), ParameterSerializer.header(spec, value.get()));
                default -> {
                    return Optional.empty(); // cookies and querystrings
                }
            }
        }
        if (!credentials(api, operation, headers, query)) {
            return Optional.empty();
        }
        var body = Optional.<String>empty();
        var requestBody = operation.operation().requestBody();
        if (requestBody.isPresent()) {
            var json = jsonBody(operation);
            if (json.isEmpty()) {
                return Optional.empty();
            }
            var example = mediaTypeExample(requestBody.get().content().get("application/json"), examples, Direction.REQUEST);
            if (example.isEmpty()) {
                return Optional.empty();
            }
            body = example.map(JsonWriter::compact);
            headers.put("Content-Type", "application/json");
        }
        return Optional.of(new Message(operation.method(), serverPath(api) + path, query, headers, body));
    }

    private static Optional<JsonValue> example(Parameter parameter, ExampleGenerator examples) {
        return parameter.example().or(() -> parameter.examples().values().stream().flatMap(e -> e.data().stream()).findFirst())
            .or(() -> parameter.effectiveSchema().flatMap(schema -> examples.example(schema, Direction.REQUEST)));
    }

    private static Optional<JsonValue> mediaTypeExample(MediaType mediaType, ExampleGenerator examples, Direction direction) {
        return mediaType.example().or(() -> mediaType.examples().values().stream().flatMap(e -> e.data().stream()).findFirst())
            .or(() -> mediaType.schema().flatMap(schema -> examples.example(schema, direction)));
    }

    /** The schema of the operation's JSON request body. */
    static Optional<Schema> jsonBody(ApiOperation operation) {
        return operation.operation().requestBody().map(body -> body.content().get("application/json")).flatMap(MediaType::schema);
    }

    /** The schema of the JSON response of {@code status}. */
    static Optional<Schema> jsonResponse(ApiOperation operation, int status) {
        return operation.operation().responses().forStatus(status).map(response -> response.content().get("application/json")).flatMap(MediaType::schema);
    }

    /** Example JSON bodies (or none) of the fixed statuses whose responses describe JSON or nothing. */
    static Map<Integer, Optional<JsonValue>> responses(OpenApiDocument api, ApiOperation operation, ExampleGenerator examples) {
        var responses = new LinkedHashMap<Integer, Optional<JsonValue>>();
        operation.operation().responses().byStatus().forEach((status, response) -> {
            if (!status.matches("[0-9]{3}") || !response.headers().values().stream().noneMatch(header -> header.required())) {
                return;
            }
            if (response.content().isEmpty()) {
                responses.put(Integer.parseInt(status), Optional.empty());
            } else if (response.content().size() == 1 && response.content().containsKey("application/json")) {
                mediaTypeExample(response.content().get("application/json"), examples, Direction.RESPONSE)
                    .ifPresent(body -> responses.put(Integer.parseInt(status), Optional.of(body)));
            }
        });
        return responses;
    }

    /** Credentials for the first security requirement; false when they cannot be made up (mutual TLS). */
    private static boolean credentials(OpenApiDocument api, ApiOperation operation, Map<String, String> headers, List<Map.Entry<String, String>> query) {
        var requirement = operation.security().stream().findFirst();
        if (requirement.isEmpty()) {
            return true;
        }
        var schemes = api.model().components().securitySchemes();
        for (var name : requirement.get().schemes().keySet()) {
            switch (schemes.get(name)) {
                case SecurityScheme.ApiKey key when key.in() == ParameterLocation.HEADER -> headers.put(key.name(), "secret");
                case SecurityScheme.ApiKey key when key.in() == ParameterLocation.QUERY -> query.add(Map.entry(key.name(), "secret"));
                case SecurityScheme.Http http when http.scheme().toLowerCase(Locale.ROOT).equals("basic") -> headers.put("Authorization", "Basic dXNlcjpwYXNz");
                case SecurityScheme.Http http -> headers.put("Authorization", "Bearer token");
                case SecurityScheme.OAuth2 oauth -> headers.put("Authorization", "Bearer token");
                case SecurityScheme.OpenIdConnect oidc -> headers.put("Authorization", "Bearer token");
                case null, default -> {
                    return false;
                }
            }
        }
        return true;
    }
}
