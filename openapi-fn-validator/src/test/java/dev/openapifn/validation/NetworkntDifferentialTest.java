package dev.openapifn.validation;

import static dev.openapifn.validation.Interactions.PROJECT;
import static dev.openapifn.validation.Interactions.valid;
import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.InvalidSchemaRefException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import dev.openapifn.generator.example.ExampleGenerator;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.DocumentParser;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.SpecVersion;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Direction;
import dev.openapifn.schema.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Body validation compared with an independent JSON Schema implementation for OpenAPI: networknt's
 * json-schema-validator, with its OpenAPI 3.0 and 3.1 dialects (the one Atlassian's validator is
 * built on, used directly so that it judges the 3.1 and 3.2 descriptions Atlassian cannot load, see
 * {@link AtlassianDifferentialTest}). For every operation of the descriptions, a valid request is
 * built, and for its JSON request body and each JSON response body, every node of the body is
 * mutated: replaced by a value of another type, by {@code null}, by edge values (empty, negative,
 * fractional, huge), and objects lose each property or gain an unknown one, arrays are emptied or
 * repeat an item. openapi-fn judges each message, networknt the body against the media type's
 * schema, with formats asserted and {@code readOnly} (requests) or {@code writeOnly} (responses)
 * enforced; both must agree.
 */
class NetworkntDifferentialTest {

    /** Every description of the samples and fixtures, and the OpenAPI Initiative's that describe JSON. */
    private static List<Path> corpus() {
        var corpus = new ArrayList<Path>();
        for (var sample : List.of("petstore/src/main/openapi/petstore.yaml", "museum/src/main/openapi/openapi.yaml", "train-travel/src/main/openapi/openapi.yaml",
            "train-travel-webflux/src/main/openapi/openapi.yaml", "galaxy/src/main/openapi/openapi.yaml")) {
            corpus.add(PROJECT.resolve("samples").resolve(sample));
        }
        for (var fixture : List.of("shop/openapi.yaml", "edge/openapi.yaml", "variants/openapi.yaml")) {
            corpus.add(PROJECT.resolve("openapi-fn-generator/src/test/resources").resolve(fixture));
        }
        var resources = PROJECT.resolve("openapi-fn-validator/src/test/resources");
        corpus.add(resources.resolve("petstore/openapi.yaml"));
        corpus.add(resources.resolve("validation/api.yaml"));
        for (var version : List.of("v3.0", "v3.1", "v3.2")) {
            try (var files = Files.list(resources.resolve("oas-suite").resolve(version).resolve("pass"))) {
                files.filter(file -> read(file).contains("application/json")).sorted().forEach(corpus::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return corpus;
    }

    /** One body and its mutations, judged by both validators. */
    private record Body(String name, SpecVersion version, Map<String, Judgement> judgements) {
    }

    private record Judgement(boolean ours, List<String> theirs, String detail) {

        boolean agree() {
            return ours == theirs.isEmpty();
        }
    }

    /**
     * Where networknt errs: in OpenAPI 3.0 descriptions, it takes an integer written with an exponent
     * ({@code 1E+15}) for a number that is not an integer, by the rule of JSON Schema draft 4.
     * OpenAPI 3.0.4, which clarifies every 3.0 version, defines integers mathematically: "both
     * {@code 1} and {@code 1.0} are equivalent, and are both considered to be integers" (Data
     * Types). openapi-fn must judge these by the specification; should networknt come to agree, the
     * test tells.
     */
    private static boolean networkntErrs(Body body, String mutation, Judgement judgement) {
        return body.version() == SpecVersion.V3_0 && mutation.endsWith(" huge") && judgement.ours()
            && judgement.theirs().stream().allMatch(error -> error.endsWith("number found, integer expected"));
    }

    /**
     * Scalar Galaxy refers to {@code #/components/...} from inside schemas with an {@code $id},
     * which JSON Schema resolves against the {@code $id}: openapi-fn falls back to the document with
     * a warning, networknt cannot resolve them (nor can Atlassian load the description). Bodies of
     * such schemas are left out, by their names and networknt's error.
     */
    private static final Map<String, String> UNRESOLVABLE = new LinkedHashMap<>();

    @TestFactory
    Stream<DynamicTest> bothValidatorsJudgeBodiesAlike() {
        var bodies = new ArrayList<Body>();
        for (var file : corpus()) {
            bodies.addAll(bodies(file));
        }
        assertThat(UNRESOLVABLE).isNotEmpty().allSatisfy((name, error) -> {
            assertThat(name).startsWith("samples/galaxy/");
            assertThat(error).matches("Reference /components/schemas/[A-Za-z]+ cannot be resolved");
        });
        assertThat(bodies).filteredOn(body -> body.name().startsWith("samples/galaxy/")).isNotEmpty();
        var judgements = bodies.stream().flatMap(body -> body.judgements().values().stream()).toList();
        assertThat(bodies).hasSizeGreaterThan(100);
        assertThat(judgements).hasSizeGreaterThan(5000);
        assertThat(judgements).filteredOn(j -> j.agree() && j.ours()).hasSizeGreaterThan(1000);
        assertThat(judgements).filteredOn(j -> j.agree() && !j.ours()).hasSizeGreaterThan(1000);
        assertThat(bodies).anySatisfy(body -> assertThat(body.judgements()).anySatisfy((mutation, judgement) -> {
            assertThat(judgement.agree()).isFalse();
            assertThat(networkntErrs(body, mutation, judgement)).isTrue();
        }));
        return bodies.stream().map(body -> DynamicTest.dynamicTest(body.name(), () -> {
            var disagreements = new LinkedHashMap<String, String>();
            body.judgements().forEach((mutation, judgement) -> {
                if (!judgement.agree() && !networkntErrs(body, mutation, judgement)) {
                    disagreements.put(mutation, (judgement.ours() ? "valid" : "invalid") + " for openapi-fn, " + (judgement.theirs().isEmpty() ? "valid"
                        : "invalid") + " for networknt: " + judgement.detail());
                }
            });
            assertThat(disagreements).as(body.name()).isEmpty();
        }));
    }

    private static List<Body> bodies(Path file) {
        var api = OpenApiLoader.defaults().load(file).orElseThrow(d -> new AssertionError(d.toString()));
        var ours = OpenApiValidator.of(api);
        var embedded = embedded(file.getParent());
        var registry = SchemaRegistry.withDefaultDialect(api.model().version() == SpecVersion.V3_0 ? Dialects.getOpenApi30() : Dialects.getOpenApi31(),
            builder -> builder.schemas(iri -> iri.startsWith("file:") ? read(Path.of(URI.create(iri))) : embedded.get(iri)));
        var version = api.model().version();
        var examples = new ExampleGenerator(api.schemas());
        var label = PROJECT.relativize(file).toString();
        var bodies = new ArrayList<Body>();
        for (var operation : api.operations()) {
            var valid = valid(api, operation, examples);
            if (valid.isEmpty()) {
                continue;
            }
            var message = valid.get();
            var name = label + " " + operation.method() + " " + operation.path();
            var request = ours.validateRequest(message.ours());
            var match = request.match().orElseThrow(() -> new AssertionError(name + " matches no operation: " + request.report().errors()));
            operation.operation().requestBody().map(body -> body.content().get("application/json")).ifPresent(mediaType -> {
                var schema = mediaType.schema().orElseThrow();
                bodyExamples(mediaType, examples, Direction.REQUEST).forEach((example, body) -> judge(name + " request body, " + example, version, () -> {
                    var judgements = new LinkedHashMap<String, Judgement>();
                    mutations(body).forEach((mutation, mutated) -> {
                        var our = ours.validateRequest(message.withBody(mutated).ours()).report().errors();
                        var their = theirs(registry, schema, mutated, Direction.REQUEST);
                        judgements.put(mutation, new Judgement(our.isEmpty(), their, JsonWriter.compact(mutated) + "; openapi-fn: " + our
                            + "; networknt: " + their));
                    });
                    return judgements;
                }, bodies));
            });
            operation.operation().responses().byStatus().forEach((status, response) -> {
                var mediaType = response.content().get("application/json");
                if (!status.matches("[0-9]{3}") || mediaType == null || mediaType.schema().isEmpty() || response.headers().values().stream()
                    .anyMatch(header -> header.required())) {
                    return;
                }
                var schema = mediaType.schema().get();
                var described = name + " " + status + " response, ";
                bodyExamples(mediaType, examples, Direction.RESPONSE).forEach((example, body) -> judge(described + example, version, () -> {
                    var judgements = new LinkedHashMap<String, Judgement>();
                    mutations(body).forEach((mutation, mutated) -> {
                        var text = JsonWriter.compact(mutated);
                        var our = ours.validateResponse(match, new HttpResponse(Integer.parseInt(status), Headers.of("Content-Type", "application/json"),
                            Optional.of(text.getBytes(StandardCharsets.UTF_8)))).errors();
                        var their = theirs(registry, schema, mutated, Direction.RESPONSE);
                        judgements.put(mutation, new Judgement(our.isEmpty(), their, text + "; openapi-fn: " + our + "; networknt: " + their));
                    });
                    return judgements;
                }, bodies));
            });
        }
        return bodies;
    }

    /**
     * The schema resources the descriptions in {@code directory} embed, by their absolute
     * {@code $id}, as JSON: networknt, a JSON Schema validator, does not look for them in OpenAPI
     * descriptions.
     */
    private static Map<String, String> embedded(Path directory) {
        var embedded = new LinkedHashMap<String, String>();
        try (var files = Files.list(directory)) {
            files.filter(file -> file.toString().endsWith(".yaml")).forEach(file -> DocumentParser.parse(file.toUri(), read(file)).toOptional()
                .ifPresent(document -> collect(document.root(), embedded)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return embedded;
    }

    private static void collect(JsonValue node, Map<String, String> embedded) {
        switch (node) {
            case JsonObject object -> {
                object.string("$id").filter(id -> URI.create(id).isAbsolute())
                    .ifPresent(id -> embedded.put(id.replaceFirst("#$", ""), JsonWriter.compact(object)));
                object.members().values().forEach(member -> collect(member, embedded));
            }
            case JsonArray array -> array.values().forEach(item -> collect(item, embedded));
            default -> {
            }
        }
    }

    /** Adds the judgements of a body, or notes that networknt cannot resolve its schema. */
    private static void judge(String name, SpecVersion version, Supplier<Map<String, Judgement>> judgements, List<Body> bodies) {
        try {
            bodies.add(new Body(name, version, judgements.get()));
        } catch (InvalidSchemaRefException e) {
            UNRESOLVABLE.put(name, e.getMessage());
        }
    }

    /** The media type's examples, and one generated from its schema, by name. */
    private static Map<String, JsonValue> bodyExamples(MediaType mediaType, ExampleGenerator examples, Direction direction) {
        var bodies = new LinkedHashMap<String, JsonValue>();
        mediaType.example().ifPresent(example -> bodies.put("the example", example));
        mediaType.examples().forEach((name, example) -> example.data().ifPresent(data -> bodies.put("example " + name, data)));
        mediaType.schema().flatMap(schema -> examples.example(schema, direction)).filter(generated -> !bodies.containsValue(generated))
            .ifPresent(generated -> bodies.put("a generated example", generated));
        return bodies;
    }

    /** networknt's findings on {@code body} against {@code schema}. */
    private static List<String> theirs(SchemaRegistry registry, Schema schema, JsonValue body, Direction direction) {
        var their = registry.getSchema(SchemaLocation.of(schema.location().toString()));
        return their.validate(JsonWriter.compact(body), InputFormat.JSON, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)
            .readOnly(direction == Direction.REQUEST ? true : null).writeOnly(direction == Direction.RESPONSE ? true : null))).stream()
            .map(error -> error.toString()).toList();
    }

    /** The body itself and its mutations, by a description of the mutation (the JSON Pointer of the node and what became of it). */
    private static Map<String, JsonValue> mutations(JsonValue body) {
        var mutations = new LinkedHashMap<String, JsonValue>();
        mutations.put("as it is", body);
        mutate("", body, mutated -> mutated, mutations);
        return mutations;
    }

    private static void mutate(String pointer, JsonValue node, UnaryOperator<JsonValue> rebuild, Map<String, JsonValue> out) {
        var at = pointer.isEmpty() ? "the body" : pointer;
        if (!(node instanceof JsonNull)) {
            out.put(at + " null", rebuild.apply(JsonNull.INSTANCE));
        }
        switch (node) {
            case JsonObject object -> {
                out.put(at + " an array", rebuild.apply(JsonArray.EMPTY));
                out.put(at + " with an unknown property", rebuild.apply(object.with("unknownProperty", JsonString.of("x"))));
                for (var key : object.keys()) {
                    out.put(at + " without " + key, rebuild.apply(object.without(key)));
                    mutate(pointer + "/" + key.replace("~", "~0").replace("/", "~1"), object.get(key).orElseThrow(),
                        child -> rebuild.apply(object.with(key, child)), out);
                }
            }
            case JsonArray array -> {
                out.put(at + " an object", rebuild.apply(JsonObject.EMPTY));
                if (!array.isEmpty()) {
                    out.put(at + " empty", rebuild.apply(JsonArray.EMPTY));
                    var repeated = new ArrayList<>(array.values());
                    repeated.add(array.values().getFirst());
                    out.put(at + " with a repeated item", rebuild.apply(new JsonArray(repeated)));
                }
                for (var i = 0; i < array.size(); i++) {
                    var index = i;
                    mutate(pointer + "/" + i, array.values().get(i), child -> {
                        var items = new ArrayList<>(array.values());
                        items.set(index, child);
                        return rebuild.apply(new JsonArray(items));
                    }, out);
                }
            }
            case JsonString string -> {
                out.put(at + " a number", rebuild.apply(JsonNumber.of(123)));
                out.put(at + " empty", rebuild.apply(JsonString.of("")));
                out.put(at + " a long string", rebuild.apply(JsonString.of("x".repeat(300))));
                out.put(at + " not of any format", rebuild.apply(JsonString.of("~ %")));
            }
            case JsonNumber number -> {
                out.put(at + " a string", rebuild.apply(JsonString.of(number.value().toPlainString())));
                out.put(at + " negative", rebuild.apply(JsonNumber.of(-1)));
                out.put(at + " a fraction", rebuild.apply(JsonNumber.of(new java.math.BigDecimal("0.5"))));
                out.put(at + " huge", rebuild.apply(JsonNumber.of(new java.math.BigDecimal("1e15"))));
            }
            case JsonBoolean bool -> out.put(at + " a string", rebuild.apply(JsonString.of(Boolean.toString(bool.value()))));
            case JsonNull ignored -> out.put(at + " a string", rebuild.apply(JsonString.of("x")));
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
