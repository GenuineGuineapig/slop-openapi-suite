package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.Document;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.DocumentParser;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.schema.Dialect;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.SchemaError;
import dev.openapifn.schema.SchemaRegistry;
import dev.openapifn.schema.SchemaValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The OpenAPI Initiative's JSON Schemas for OpenAPI descriptions as an independent oracle (see
 * {@code oas-schemas/README.md}). They are evaluated by {@link SchemaValidator}, which passes every
 * case of the JSON Schema test suite. Every description the tests know is judged by the official
 * schema of its version, and openapi-fn must judge it the same way: the OpenAPI Initiative's own
 * cases, the samples' (real-world) descriptions and the fixtures. Swagger 2.0 descriptions must be
 * valid 2.0, and what openapi-fn converts them to valid 3.0.
 */
class OfficialSchemaTest {

    private static final Path RESOURCES = resources();
    /** The repository: the directory with the build's {@code .mvn}. */
    private static final Path PROJECT = Stream.iterate(RESOURCES.toAbsolutePath(), dir -> dir != null, Path::getParent)
        .filter(dir -> Files.isDirectory(dir.resolve(".mvn"))).findFirst().orElseThrow();
    private static final Map<URI, String> SCHEMAS = schemas();
    private static final Map<String, URI> ROOTS = Map.of(
        "2.0", URI.create("http://swagger.io/v2/schema.json"),
        "3.0", URI.create("https://spec.openapis.org/oas/3.0/schema/WORK-IN-PROGRESS"),
        "3.1", URI.create("https://spec.openapis.org/oas/3.1/schema-base/WORK-IN-PROGRESS"),
        "3.2", URI.create("https://spec.openapis.org/oas/3.2/schema-base/WORK-IN-PROGRESS"));
    private static final Map<String, Oracle> ORACLES = new HashMap<>();

    /** Valid descriptions that reference a document on example.com, which does not exist. */
    private static final Set<String> UNRESOLVABLE = Set.of("v3.1/pass/security-scheme-object-examples.yaml",
        "v3.2/pass/security-scheme-object-examples.yaml");

    /** An official schema, ready to validate descriptions. */
    private record Oracle(Schema root, SchemaValidator validator) {
        List<SchemaError> judge(JsonValue description) {
            return validator.validate(root, description);
        }
    }

    private static Path resources() {
        try {
            return Path.of(OfficialSchemaTest.class.getResource("/oas-schemas").toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The official schemas and the JSON Schema meta-schemas they build on, by their identifiers. */
    private static Map<URI, String> schemas() {
        var schemas = new HashMap<URI, String>();
        for (var directory : List.of("oas-schemas", "json-schema-test-suite/metaschemas")) {
            files(RESOURCES.resolve(directory), ".json", ".yaml").forEach(file -> {
                var text = read(file);
                var root = (JsonObject) parse(file.toUri(), text).root();
                var id = root.string("$id").or(() -> root.string("id")).orElseThrow().replaceFirst("#$", "");
                schemas.put(URI.create(id), text);
            });
        }
        return schemas;
    }

    private static Oracle oracle(String version) {
        return ORACLES.computeIfAbsent(version, v -> {
            var loader = new DocumentLoader(ResourceLoaders.inMemory(SCHEMAS));
            var uri = ROOTS.get(v);
            var document = loader.load(uri).orElseThrow(d -> new AssertionError(d.toString()));
            var dialect = v.equals("2.0") || v.equals("3.0") ? Dialect.DRAFT_4 : Dialect.DRAFT_2020_12;
            var root = Schema.at(document.root(), uri, JsonPointer.ROOT, uri, dialect);
            var build = SchemaRegistry.build(List.of(root), loader, dialect);
            assertThat(build.diagnostics()).as("the official %s schema", v).isEmpty();
            return new Oracle(root, new SchemaValidator(build.registry(), SchemaValidator.Options.DEFAULT.withAssertFormats(false)));
        });
    }

    /** The version a description declares: {@code 2.0}, {@code 3.0}, {@code 3.1} or {@code 3.2}. */
    private static String version(JsonValue description) {
        var object = (JsonObject) description;
        return object.string("swagger").orElseGet(() -> object.string("openapi").orElseThrow().substring(0, 3));
    }

    private static Document parse(URI uri, String text) {
        return DocumentParser.parse(uri, text).orElseThrow(d -> new AssertionError(d.toString()));
    }

    private static JsonValue description(Path file) {
        return parse(file.toUri(), read(file)).root();
    }

    /** The official schema's findings on the description in {@code file}. */
    private static List<SchemaError> officially(Path file) {
        var description = description(file);
        return oracle(version(description)).judge(description);
    }

    /** openapi-fn's errors loading the description in {@code file}. */
    private static List<Diagnostic> errors(Path file) {
        return OpenApiLoader.defaults().load(file).fold(api -> api.warnings(), errors -> errors).stream().filter(Diagnostic::isError).toList();
    }

    @TestFactory
    Stream<DynamicTest> theOfficialCasesAreJudgedAlike() {
        var root = RESOURCES.resolve("oas-suite");
        var files = files(root, ".yaml").toList();
        assertThat(files).hasSizeGreaterThan(110);
        return files.stream().map(file -> {
            var name = root.relativize(file).toString();
            var valid = name.contains("/pass/");
            return DynamicTest.dynamicTest(name, () -> {
                assertThat(officially(file)).as("the official schema on %s", name).matches(errors -> errors.isEmpty() == valid,
                    valid ? "no findings" : "findings");
                var errors = errors(file);
                if (UNRESOLVABLE.contains(name)) {
                    assertThat(errors).as(name).allMatch(d -> d.message().startsWith("Cannot resolve reference 'https://example.com/"));
                } else {
                    assertThat(errors.isEmpty()).as("openapi-fn on %s: %s", name, errors).isEqualTo(valid);
                }
            });
        });
    }

    /** The samples' descriptions (Train Travel, Scalar Galaxy, Redocly Museum, a petstore) and the fixtures of the code generator and the core. */
    @TestFactory
    Stream<DynamicTest> realDescriptionsAreValidForBoth() {
        var corpus = new ArrayList<Path>();
        for (var sample : List.of("petstore/src/main/openapi/petstore.yaml", "museum/src/main/openapi/openapi.yaml", "train-travel/src/main/openapi/openapi.yaml",
            "train-travel-webflux/src/main/openapi/openapi.yaml", "galaxy/src/main/openapi/openapi.yaml")) {
            corpus.add(PROJECT.resolve("samples").resolve(sample));
        }
        for (var fixture : List.of("shop/openapi.yaml", "edge/openapi.yaml", "variants/openapi.yaml")) {
            corpus.add(PROJECT.resolve("openapi-fn-generator/src/test/resources").resolve(fixture));
        }
        for (var fixture : List.of("petstore/openapi.yaml", "validation/api.yaml")) {
            corpus.add(RESOURCES.resolve(fixture));
        }
        return corpus.stream().map(file -> DynamicTest.dynamicTest(PROJECT.relativize(file).toString(), () -> {
            assertThat(file).exists();
            assertThat(officially(file)).as("the official schema on %s", file).isEmpty();
            assertThat(errors(file)).as("openapi-fn on %s", file).isEmpty();
        }));
    }

    /**
     * Swagger 2.0 descriptions are valid 2.0 (but for a definition of {@code type: file}, which is
     * common although 2.0 allows it for parameters and responses only: openapi-fn warns about it),
     * and openapi-fn converts them to valid 3.0.
     */
    @TestFactory
    Stream<DynamicTest> convertedDescriptionsAreValidOpenApi30() {
        var corpus = List.of(RESOURCES.resolve("swagger2/petstore.yaml"), RESOURCES.resolve("swagger2/multi/api.yaml"),
            PROJECT.resolve("openapi-fn-generator/src/test/resources/swagger2/petstore.yaml"));
        return corpus.stream().map(file -> DynamicTest.dynamicTest(PROJECT.relativize(file).toString(), () -> {
            var findings = officially(file).stream().map(SchemaError::toString).toList();
            var warnings = OpenApiLoader.defaults().load(file).orElseThrow(d -> new AssertionError(d.toString())).warnings().stream()
                .map(Diagnostic::message).toList();
            if (file.endsWith("petstore.yaml")) {
                assertThat(findings).as("the official 2.0 schema on %s", file).containsExactly("/definitions/Blob/type: must match at least one schema in anyOf");
                assertThat(warnings).contains("definition 'Blob' uses type 'file', which Swagger 2.0 allows for parameters and responses only; it is "
                    + "converted to a binary string");
            } else {
                assertThat(findings).as("the official 2.0 schema on %s", file).isEmpty();
            }
            var loader = new DocumentLoader(ResourceLoaders.defaults());
            var document = loader.load(file.toUri()).orElseThrow(d -> new AssertionError(d.toString()));
            var bundled = Swagger2Bundler.bundle((JsonObject) document.root(), document.uri(), location -> loader.load(location).toOptional().map(Document::root));
            var converted = Swagger2Converter.convert(bundled).root();
            assertThat(oracle("3.0").judge(converted)).as("the official 3.0 schema on %s converted", file).isEmpty();
        }));
    }

    @Test
    void theOracleRejectsWhatItShould() {
        var description = parse(URI.create("mem:/broken.yaml"), "openapi: 3.1.0\ninfo: {title: t}\npaths: {/x: {get: {responses: {'200': {}}}}}\n").root();
        assertThat(oracle("3.1").judge(description)).isNotEmpty();
    }

    private static Stream<Path> files(Path directory, String... extensions) {
        try (var walk = Files.walk(directory)) {
            return walk.filter(file -> Stream.of(extensions).anyMatch(file.toString()::endsWith)).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
