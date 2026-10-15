package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.load.Diagnostic;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The OpenAPI Initiative's schema tests: descriptions under {@code pass} are valid and must load
 * without errors, descriptions under {@code fail} are invalid and must be rejected.
 */
class OpenApiConformanceSuiteTest {

    /**
     * Valid descriptions that reference a document on example.com, which does not exist: loading
     * them fails only on that reference.
     */
    private static final Set<String> UNRESOLVABLE = Set.of("v3.1/pass/security-scheme-object-examples.yaml",
        "v3.2/pass/security-scheme-object-examples.yaml");

    @TestFactory
    Stream<DynamicTest> officialSchemaTests() throws IOException, URISyntaxException {
        var root = Path.of(OpenApiConformanceSuiteTest.class.getResource("/oas-suite").toURI());
        var files = Files.walk(root).filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
        assertThat(files).hasSizeGreaterThan(100);
        return files.stream().map(file -> {
            var name = root.relativize(file).toString();
            var valid = name.contains("/pass/");
            return DynamicTest.dynamicTest(name, () -> {
                var loaded = OpenApiLoader.defaults().load(file);
                if (UNRESOLVABLE.contains(name)) {
                    assertThat(loaded.failure().orElseThrow()).as(name).allMatch(d -> d.message().startsWith("Cannot resolve reference 'https://example.com/"));
                } else if (valid) {
                    assertThat(loaded.failure()).as(name).isEmpty();
                } else {
                    List<Diagnostic> diagnostics = loaded.fold(api -> api.warnings(), errors -> errors);
                    assertThat(diagnostics).as(name).anyMatch(Diagnostic::isError);
                }
            });
        });
    }
}
