package dev.openapifn.codegen.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static Path shop() throws Exception {
        return Path.of(MainTest.class.getResource("/shop/openapi.yaml").toURI());
    }

    @Test
    void generatesIntoTheOutputDirectories() throws Exception {
        var code = run("--spec", shop().toString(), "--name", "shop", "--package", "com.example.shop", "--output", dir.resolve("src").toString(),
            "--test-output", dir.resolve("test").toString(), "--resources-output", dir.resolve("resources").toString(), "--base-path", "/base",
            "--no-client");
        assertThat(code).as(err()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("Generated ").endsWith(" files for 'shop'\n");
        assertThat(dir.resolve("src/com/example/shop/api/PetsController.java")).exists();
        assertThat(dir.resolve("src/com/example/shop/api/PetsClient.java")).doesNotExist();
        assertThat(dir.resolve("test/com/example/shop/api/PetsControllerTest.java")).exists();
        assertThat(dir.resolve("resources/META-INF/openapi-fn/shop/spec.properties")).content().contains("base-path=/base");
    }

    @Test
    void generatesForWebFlux() throws Exception {
        var code = run("--spec", shop().toString(), "--reactive", "--name", "shop", "--package", "com.example.shop", "--output", dir.toString());
        assertThat(code).as(err()).isZero();
        assertThat(dir.resolve("com/example/shop/api/PetsController.java")).content().contains("ReactiveRuntime");
        assertThat(dir.resolve("com/example/shop/api/PetsControllerTest.java")).content().contains("@WebFluxTest");
    }

    @Test
    void writesEverythingToTheOutputByDefault() throws Exception {
        var spec = Files.writeString(dir.resolve("api.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            jsonSchemaDialect: https://example.com/custom-dialect
            paths: {}
            """);
        var code = run("--spec", spec.toString(), "--name", "t", "--package", "t", "--output", dir.resolve("out").toString(), "--no-server",
            "--no-tests");
        assertThat(code).as(err()).isZero();
        assertThat(err()).contains("Unknown jsonSchemaDialect 'https://example.com/custom-dialect'");
        assertThat(dir.resolve("out/META-INF/openapi-fn/t/spec.properties")).exists();
        assertThat(dir.resolve("out/t/TClients.java")).exists();
    }

    @Test
    void rejectsBadArguments() {
        assertThat(run("--spec", "a.yaml", "stray")).isEqualTo(2);
        assertThat(err()).startsWith("Unexpected argument 'stray'\nUsage: ");
        err.reset();
        assertThat(run("--spec")).isEqualTo(2);
        assertThat(err()).startsWith("Unexpected argument '--spec'");
        err.reset();
        assertThat(run("--spec", "a.yaml", "--name", "n", "--package", "p")).isEqualTo(2);
        assertThat(err()).startsWith("Missing --output\n");
    }

    @Test
    void reportsInvalidDescriptions() throws Exception {
        var spec = Files.writeString(dir.resolve("api.yaml"), "openapi: 3.1.0\n");
        assertThat(run("--spec", spec.toString(), "--name", "n", "--package", "p", "--output", dir.toString())).isEqualTo(1);
        assertThat(err()).contains("info");
    }

    @Test
    void reportsWriteFailures() throws Exception {
        var blocker = Files.writeString(dir.resolve("file"), "");
        assertThat(run("--spec", shop().toString(), "--name", "shop", "--package", "p", "--output", blocker.resolve("out").toString())).isEqualTo(1);
        assertThat(err()).startsWith("Cannot write generated files: ");
    }

    @Test
    void mainExitsWithTheCode() {
        var codes = new ArrayList<Integer>();
        var previous = Main.exit;
        Main.exit = codes::add;
        try {
            Main.main(new String[] {"--name", "n"});
        } finally {
            Main.exit = previous;
        }
        assertThat(codes).containsExactly(2);
    }
}
