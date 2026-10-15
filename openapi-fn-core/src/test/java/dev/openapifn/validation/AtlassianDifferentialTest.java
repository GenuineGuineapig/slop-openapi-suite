package dev.openapifn.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import dev.openapifn.example.ExampleGenerator;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.http.UrlCodec;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Direction;
import dev.openapifn.schema.Schema;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Request and response validation compared with an independent implementation: Atlassian's
 * swagger-request-validator (openapi-request-validator-core), which openapi-fn replaces. For every
 * operation of the samples' third-party descriptions, valid requests and responses are built from
 * the descriptions, and invalid ones from them (a required parameter left out, a required property
 * left out, a body of the wrong type, a status that is not described). Both validators must judge
 * each message alike.
 */
class AtlassianDifferentialTest {

    private static final Path PROJECT = project();

    private static Path project() {
        try {
            return Path.of(AtlassianDifferentialTest.class.getResource("/oas-schemas").toURI()).resolve("../../../../..").normalize();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A message, as both validators get it. */
    private record Message(String method, String path, List<Map.Entry<String, String>> query, Map<String, String> headers, Optional<String> body) {

        Message with(List<Map.Entry<String, String>> otherQuery, Map<String, String> otherHeaders, Optional<String> otherBody) {
            return new Message(method, path, otherQuery, otherHeaders, otherBody);
        }

        HttpRequest ours() {
            var request = HttpRequest.of(method, path + (query.isEmpty() ? "" : "?" + ParameterSerializer.queryString(query)));
            for (var header : headers.entrySet()) {
                request = request.withHeader(header.getKey(), header.getValue());
            }
            var head = request;
            return body.map(text -> head.withBody(text.getBytes(StandardCharsets.UTF_8))).orElse(head);
        }

        SimpleRequest theirs() {
            var builder = new SimpleRequest.Builder(method, path);
            var decoded = new LinkedHashMap<String, List<String>>();
            query.forEach(pair -> decoded.computeIfAbsent(UrlCodec.decode(pair.getKey(), false), key -> new ArrayList<>())
                .add(UrlCodec.decode(pair.getValue(), false)));
            decoded.forEach(builder::withQueryParam);
            headers.forEach(builder::withHeader);
            body.ifPresent(builder::withBody);
            return builder.build();
        }
    }

    /** A judgement to compare. */
    private record Case(String name, boolean valid, boolean ours, boolean theirs, String detail) {
    }

    /**
     * The descriptions both validators can load: the samples' 3.1 descriptions and the OpenAPI
     * Initiative's 3.0 example descriptions (Atlassian supports neither 3.2, which the other samples
     * and fixtures use, nor the Galaxy, see {@link #atlassianCannotLoadEverything}).
     */
    private static final List<String> CORPUS = List.of("samples/petstore/src/main/openapi/petstore.yaml", "samples/museum/src/main/openapi/openapi.yaml",
        "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/api-with-examples.yaml", "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/callback-example.yaml",
        "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/link-example.yaml", "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/petstore-expanded.yaml",
        "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/petstore.yaml", "openapi-fn-core/src/test/resources/oas-suite/v3.0/pass/uspto.yaml");

    /**
     * Where Atlassian errs, by case. It rejects objects composed with {@code allOf} for properties
     * of their members, as if they did not allow additional properties, even with combinator
     * resolution (JSON Schema allows additional properties unless {@code additionalProperties}
     * forbids them, and nothing in these schemas does); and it resolves a discriminator mapping
     * ({@code #/Cat}) against the root document rather than the document it is written in. openapi-fn
     * must judge these by the specifications; should Atlassian come to agree, the case moves back to
     * the others.
     */
    private static final Map<String, String> ATLASSIAN_ERRS = Map.of(
        "museum POST /tickets", "BuyMuseumTickets: type: object next to allOf of an inline schema and Ticket",
        "museum POST /tickets 201 response", "MuseumTicketsConfirmation: allOf of Ticket and an inline schema",
        "petstore GET /pets/{petId} 200 response", "Pet in schemas.yaml: discriminator mapping '#/Cat', and Cat's allOf");

    @TestFactory
    Stream<DynamicTest> bothValidatorsJudgeAlike() {
        var cases = new ArrayList<Case>();
        for (var location : CORPUS) {
            cases.addAll(cases(PROJECT.resolve(location)));
        }
        assertThat(cases).hasSizeGreaterThan(90);
        assertThat(cases).extracting(Case::name).containsAll(ATLASSIAN_ERRS.keySet());
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            assertThat(c.ours()).as("%s is %s: %s", c.name(), c.valid() ? "valid" : "invalid", c.detail()).isEqualTo(c.valid());
            if (ATLASSIAN_ERRS.containsKey(c.name())) {
                assertThat(c.theirs()).as("Atlassian on %s (%s)", c.name(), ATLASSIAN_ERRS.get(c.name())).isNotEqualTo(c.valid());
            } else {
                assertThat(c.theirs()).as("openapi-fn and Atlassian on %s (%s): %s", c.name(), c.valid() ? "valid" : "invalid", c.detail()).isEqualTo(c.ours());
            }
        }));
    }

    private static List<Case> cases(Path file) {
        OpenApiDocument api = OpenApiLoader.defaults().load(file).orElseThrow(d -> new AssertionError(d.toString()));
        var ours = OpenApiValidator.of(api);
        var theirs = OpenApiInteractionValidator.createFor(file.toUri().toString()).withResolveCombinators(true).build();
        var examples = new ExampleGenerator(api.schemas());
        var serverPath = api.model().servers().getFirst().defaultUrl().replaceFirst("^[a-z]+://[^/]*", "").replaceFirst("/$", "");
        var relative = PROJECT.relativize(file);
        var label = relative.startsWith("samples") ? relative.getName(1).toString() : "oai " + file.getFileName().toString().replaceFirst("\\.yaml$", "");
        var cases = new ArrayList<Case>();
        for (var operation : api.operations()) {
            valid(api, operation, examples, serverPath).ifPresent(message -> {
                var name = label + " " + operation.method() + " " + operation.path();
                cases.add(request(name, true, message, ours, theirs));
                for (var parameter : operation.parameters()) {
                    if (parameter.required() && parameter.in() != ParameterLocation.PATH && parameter.in() != ParameterLocation.COOKIE) {
                        var query = message.query().stream().filter(pair -> !(parameter.in() == ParameterLocation.QUERY
                            && UrlCodec.decode(pair.getKey(), false).equals(parameter.name()))).toList();
                        var headers = new LinkedHashMap<>(message.headers());
                        if (parameter.in() == ParameterLocation.HEADER) {
                            headers.keySet().removeIf(header -> header.equalsIgnoreCase(parameter.name()));
                        }
                        cases.add(request(name + " without " + parameter.name(), false, message.with(query, headers, message.body()), ours, theirs));
                    }
                }
                jsonBody(operation).ifPresent(schema -> {
                    var body = message.body().map(text -> dev.openapifn.json.Json.parse(text).toOptional().orElseThrow());
                    var object = body.filter(JsonObject.class::isInstance).map(JsonObject.class::cast);
                    var required = api.schemas().deref(schema).required();
                    object.flatMap(o -> required.stream().filter(o::has).findFirst()).ifPresent(property -> cases.add(request(name + " body without "
                        + property, false, message.with(message.query(), message.headers(), Optional.of(JsonWriter.compact(object.get().without(property)))),
                        ours, theirs)));
                    if (object.isPresent()) {
                        cases.add(request(name + " body of the wrong type", false, message.with(message.query(), message.headers(), Optional.of("[]")), ours,
                            theirs));
                    }
                });
                // Responses are judged for the operation the request matched (the request's own case reports when it matches none).
                var matched = ours.validateRequest(message.ours()).match();
                matched.ifPresent(match -> responses(api, operation, examples).forEach((status, body) -> {
                    cases.add(response(name + " " + status + " response", true, match, message, status, body, ours, theirs));
                    if (body.filter(JsonObject.class::isInstance).isPresent() && objectSchema(api, operation, status)) {
                        cases.add(response(name + " " + status + " response of the wrong type", false, match, message, status, Optional.of(new JsonArray(List.of())),
                            ours, theirs));
                    }
                }));
                if (operation.operation().responses().defaultResponse().isEmpty()) {
                    matched.ifPresent(match -> cases.add(response(name + " undescribed status", false, match, message, 599, Optional.empty(), ours, theirs)));
                }
            });
        }
        return cases;
    }

    private static Case request(String name, boolean valid, Message message, OpenApiValidator ours, OpenApiInteractionValidator theirs) {
        var our = ours.validateRequest(message.ours()).report();
        var their = theirs.validateRequest(message.theirs());
        return new Case(name, valid, !our.hasErrors(), !their.hasErrors(), "openapi-fn: " + our.errors() + "; Atlassian: " + their.getMessages().stream()
            .filter(m -> m.getLevel() == com.atlassian.oai.validator.report.ValidationReport.Level.ERROR).map(m -> m.getKey() + " " + m.getMessage()).toList());
    }

    private static Case response(String name, boolean valid, OperationMatch match, Message message, int status, Optional<JsonValue> body,
                                 OpenApiValidator ours, OpenApiInteractionValidator theirs) {
        var headers = body.isPresent() ? Headers.of("Content-Type", "application/json") : Headers.EMPTY;
        var text = body.map(JsonWriter::compact);
        var our = ours.validateResponse(match, new HttpResponse(status, headers, text.map(t -> t.getBytes(StandardCharsets.UTF_8))));
        var builder = new SimpleResponse.Builder(status);
        body.ifPresent(b -> builder.withContentType("application/json"));
        text.ifPresent(builder::withBody);
        var their = theirs.validateResponse(message.path(), Request.Method.valueOf(message.method()), builder.build());
        return new Case(name, valid, !our.hasErrors(), !their.hasErrors(), "openapi-fn: " + our.errors() + "; Atlassian: " + their.getMessages().stream()
            .filter(m -> m.getLevel() == com.atlassian.oai.validator.report.ValidationReport.Level.ERROR).map(m -> m.getKey() + " " + m.getMessage()).toList());
    }

    /** A valid request: example values for the path and the required parameters, credentials, and a JSON body if one is described. */
    private static Optional<Message> valid(OpenApiDocument api, ApiOperation operation, ExampleGenerator examples, String serverPath) {
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
        return Optional.of(new Message(operation.method(), serverPath + path, query, headers, body));
    }

    private static Optional<JsonValue> example(Parameter parameter, ExampleGenerator examples) {
        return parameter.example().or(() -> parameter.examples().values().stream().flatMap(e -> e.data().stream()).findFirst())
            .or(() -> parameter.effectiveSchema().flatMap(schema -> examples.example(schema, Direction.REQUEST)));
    }

    private static Optional<JsonValue> mediaTypeExample(MediaType mediaType, ExampleGenerator examples, Direction direction) {
        return mediaType.example().or(() -> mediaType.examples().values().stream().flatMap(e -> e.data().stream()).findFirst())
            .or(() -> mediaType.schema().flatMap(schema -> examples.example(schema, direction)));
    }

    private static Optional<Schema> jsonBody(ApiOperation operation) {
        return operation.operation().requestBody().map(body -> body.content().get("application/json")).flatMap(MediaType::schema);
    }

    /** Example JSON bodies (or none) of the fixed statuses whose responses describe JSON or nothing. */
    private static Map<Integer, Optional<JsonValue>> responses(OpenApiDocument api, ApiOperation operation, ExampleGenerator examples) {
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

    /** Whether the JSON response of {@code status} is described as an object (so that an array is the wrong type). */
    private static boolean objectSchema(OpenApiDocument api, ApiOperation operation, int status) {
        return operation.operation().responses().forStatus(status).map(response -> response.content().get("application/json")).flatMap(MediaType::schema)
            .map(schema -> api.schemas().deref(schema).types().contains("object")).orElse(false);
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

    /**
     * Scalar Galaxy resolves {@code #/components/...} from inside schemas with an {@code $id} against
     * the document; openapi-fn falls back to the document (with a warning), Atlassian cannot load it.
     */
    @org.junit.jupiter.api.Test
    void atlassianCannotLoadEverything() {
        var galaxy = PROJECT.resolve("samples/galaxy/src/main/openapi/openapi.yaml");
        assertThat(OpenApiLoader.defaults().load(galaxy).isOk()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiInteractionValidator.createFor(galaxy.toUri().toString()).build())
            .isInstanceOf(OpenApiInteractionValidator.ApiLoadException.class).hasMessageContaining("Could not find /components/schemas/PaginatedResource");
        // OpenAPI 3.2: Atlassian reads the description as an older version.
        var trainTravel = PROJECT.resolve("samples/train-travel/src/main/openapi/openapi.yaml");
        assertThat(OpenApiLoader.defaults().load(trainTravel).isOk()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenApiInteractionValidator.createFor(trainTravel.toUri().toString()).build())
            .isInstanceOf(OpenApiInteractionValidator.ApiLoadException.class);
    }
}
