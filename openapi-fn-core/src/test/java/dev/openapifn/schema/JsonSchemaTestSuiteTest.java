package dev.openapifn.schema;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Document;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.load.SourceMap;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the official JSON-Schema-Test-Suite (vendored under {@code src/test/resources}) against
 * {@link SchemaValidator}: every required and optional test. Required tests run without format
 * assertion, {@code optional/format} runs with it, and {@code optional/content.json} asserts
 * content.
 */
class JsonSchemaTestSuiteTest {

    private static final Path ROOT = resource("/json-schema-test-suite");
    private static final Map<URI, String> REMOTES = remotes();

    @TestFactory
    Stream<DynamicNode> draft2020_12() {
        return suite("draft2020-12", Dialect.DRAFT_2020_12);
    }

    @TestFactory
    Stream<DynamicNode> draft2019_09() {
        return suite("draft2019-09", Dialect.DRAFT_2019_09);
    }

    @TestFactory
    Stream<DynamicNode> draft7() {
        return suite("draft7", Dialect.DRAFT_7);
    }

    @TestFactory
    Stream<DynamicNode> draft4() {
        return suite("draft4", Dialect.DRAFT_4);
    }

    private static Stream<DynamicNode> suite(String draft, Dialect dialect) {
        var dir = ROOT.resolve("tests").resolve(draft);
        var nodes = new ArrayList<DynamicNode>();
        files(dir).forEach(file -> nodes.add(file(draft, dir.relativize(file).toString(), file, dialect, false)));
        files(dir.resolve("optional")).forEach(file -> nodes.add(file(draft, "optional/" + file.getFileName(), file, dialect, false)));
        files(dir.resolve("optional/format")).forEach(file -> nodes.add(file(draft, "optional/format/" + file.getFileName(), file, dialect, true)));
        return nodes.stream();
    }

    private static DynamicContainer file(String draft, String name, Path file, Dialect dialect, boolean assertFormats) {
        var groups = (JsonArray) Json.parse(read(file)).orElseThrow(e -> new AssertionError(e.toString()));
        var nodes = new ArrayList<DynamicNode>();
        for (int g = 0; g < groups.size(); g++) {
            var group = (JsonObject) groups.values().get(g);
            var description = group.string("description").orElseThrow();
            var schemaJson = group.get("schema").orElseThrow();
            var uri = URI.create("mem:/suite/" + draft + "/" + name + "/" + g + ".json");
            var tests = new ArrayList<DynamicTest>();
            for (var test : group.array("tests").orElseThrow().values()) {
                var testObject = (JsonObject) test;
                var testDescription = testObject.string("description").orElseThrow();
                var key = draft + "/" + name + "/" + description + "/" + testDescription;
                var data = testObject.get("data").orElseThrow();
                var valid = testObject.bool("valid").orElseThrow();
                tests.add(DynamicTest.dynamicTest(testDescription, () -> run(key, uri, schemaJson, dialect, assertFormats, data, valid)));
            }
            nodes.add(DynamicContainer.dynamicContainer(description, tests));
        }
        return DynamicContainer.dynamicContainer(name, nodes);
    }

    private static void run(String key, URI uri, JsonValue schemaJson, Dialect dialect, boolean assertFormats, JsonValue data, boolean valid) {
        var loader = new DocumentLoader(ResourceLoaders.inMemory(REMOTES));
        loader.register(new Document(uri, schemaJson, SourceMap.EMPTY));
        var root = Schema.at(schemaJson, uri, JsonPointer.ROOT, uri, dialect);
        var build = SchemaRegistry.build(List.of(root), loader, dialect);
        // The optional content tests assert contentEncoding and contentMediaType, which are annotations by default.
        var options = SchemaValidator.Options.DEFAULT.withAssertFormats(assertFormats).withAssertContent(key.contains("/optional/content.json/"));
        var validator = new SchemaValidator(build.registry(), options);
        var errors = validator.validate(root, data);
        assertThat(errors.isEmpty())
            .as("%s: expected %s for %s but got %s", key, valid ? "valid" : "invalid", data.toJson(), SchemaValidator.describe(errors))
            .isEqualTo(valid);
    }

    private static Map<URI, String> remotes() {
        var result = new HashMap<URI, String>();
        var remotes = ROOT.resolve("remotes");
        try (var walk = Files.walk(remotes)) {
            walk.filter(Files::isRegularFile).forEach(file ->
                result.put(URI.create("http://localhost:1234/" + remotes.relativize(file).toString().replace('\\', '/')), read(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        files(ROOT.resolve("metaschemas")).forEach(file -> {
            var text = read(file);
            var json = (JsonObject) Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
            var id = json.string("$id").or(() -> json.string("id")).orElseThrow().replaceFirst("#$", "");
            result.put(URI.create(id), text);
        });
        return result;
    }

    private static List<Path> files(Path dir) {
        try (var list = Files.list(dir)) {
            return list.filter(path -> path.toString().endsWith(".json")).sorted().toList();
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

    private static Path resource(String name) {
        try {
            return Path.of(JsonSchemaTestSuiteTest.class.getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
