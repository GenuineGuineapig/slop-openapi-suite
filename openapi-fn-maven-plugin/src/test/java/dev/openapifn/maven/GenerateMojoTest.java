package dev.openapifn.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateMojoTest {

    @TempDir
    Path dir;

    private static final String API = """
        openapi: 3.1.0
        info: {title: Pets, version: 1.0.0}
        servers: [{url: 'https://example.com/api'}]
        paths:
          /pets/{id}:
            get:
              operationId: getPet
              parameters:
                - {name: id, in: path, required: true, schema: {type: integer}}
              responses:
                '200':
                  description: a pet
                  content:
                    application/json: {schema: {$ref: 'schemas/pet.yaml'}}
        """;

    private static final String PET = "{type: object, required: [name], properties: {name: {type: string}}}\n";

    private Path write(String path, String content) throws Exception {
        var file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    /** The goal as Maven configures it by default, for the description at {@code spec}. */
    private GenerateMojo mojo(String spec) {
        var mojo = new GenerateMojo();
        mojo.spec = dir.resolve(spec).toFile();
        mojo.name = "pets";
        mojo.packageName = "com.example.pets";
        mojo.remoteCache = dir.resolve("repository/.cache/openapi-fn/remote").toFile();
        mojo.outputDirectory = dir.resolve("target/generated-sources/openapi-fn").toFile();
        mojo.testOutputDirectory = dir.resolve("target/generated-test-sources/openapi-fn").toFile();
        mojo.resourcesOutputDirectory = dir.resolve("target/generated-resources/openapi-fn").toFile();
        mojo.pluginVersion = "1.0.0";
        mojo.project = new MavenProject();
        mojo.project.getBuild().setDirectory(dir.resolve("target").toString());
        return mojo;
    }

    private Path sources() {
        return dir.resolve("target/generated-sources/openapi-fn/pets");
    }

    private Path testSources() {
        return dir.resolve("target/generated-test-sources/openapi-fn/pets");
    }

    private Path resources() {
        return dir.resolve("target/generated-resources/openapi-fn/pets");
    }

    @Test
    void generatesSourcesTestsAndResourcesIntoTheBuild() throws Exception {
        write("src/main/openapi/api.yaml", API);
        write("src/main/openapi/schemas/pet.yaml", PET);
        var stale = write("target/generated-sources/openapi-fn/pets/Stale.java", "class Stale {}");
        var mojo = mojo("src/main/openapi/api.yaml");
        mojo.execute();
        assertThat(sources().resolve("com/example/pets/model/Pet.java")).exists();
        assertThat(sources().resolve("com/example/pets/api/DefaultController.java")).exists();
        assertThat(sources().resolve("com/example/pets/api/DefaultClient.java")).exists();
        assertThat(stale).doesNotExist();
        assertThat(testSources().resolve("com/example/pets/api/DefaultControllerTest.java")).exists();
        assertThat(resources().resolve("META-INF/openapi-fn/pets/spec.properties")).content().contains("name=pets").doesNotContain("base-path");
        assertThat(mojo.project.getCompileSourceRoots()).contains(sources().toString());
        assertThat(mojo.project.getTestCompileSourceRoots()).contains(testSources().toString());
        assertThat(mojo.project.getResources()).extracting(Resource::getDirectory).contains(resources().toString());
    }

    @Test
    void honoursTheToggles() throws Exception {
        write("api.yaml", API.replace("$ref: 'schemas/pet.yaml'", "type: object") + "x-unknown: 1\njsonSchemaDialect: https://example.com/custom-dialect\n");
        var mojo = mojo("api.yaml");
        mojo.basePath = "/v2";
        mojo.client = false;
        mojo.tests = false;
        mojo.execute();
        assertThat(sources().resolve("com/example/pets/api/DefaultClient.java")).doesNotExist();
        assertThat(testSources().resolve("com/example/pets/api/DefaultControllerTest.java")).doesNotExist();
        assertThat(resources().resolve("META-INF/openapi-fn/pets/spec.properties")).content().contains("base-path=/v2");
        mojo.server = false;
        mojo.execute();
        assertThat(sources().resolve("com/example/pets/api/DefaultController.java")).doesNotExist();
        mojo.reactive = true;
        mojo.client = true;
        mojo.execute();
        assertThat(sources().resolve("com/example/pets/api/DefaultClient.java")).content().contains("OpenApiWebClient");
    }

    @Test
    void generatesAgainOnlyWhenItsInputsChange() throws Exception {
        write("api.yaml", API);
        write("schemas/pet.yaml", PET);
        write("notes.md", "Not part of the description.");
        var mojo = mojo("api.yaml");
        mojo.execute();
        var marker = Files.writeString(sources().resolve("Marker.java"), "class Marker {}");
        // Up to date: nothing the description loads has changed.
        write("notes.md", "Still not part of the description.");
        mojo.execute();
        assertThat(marker).exists();
        assertThat(mojo.project.getCompileSourceRoots()).containsOnlyOnce(sources().toString());
        // A referenced document changed.
        write("schemas/pet.yaml", PET.replace("required: [name], ", ""));
        mojo.execute();
        assertThat(marker).doesNotExist();
        // An output is gone.
        Files.writeString(sources().resolve("Marker.java"), "class Marker {}");
        try (var files = Files.walk(testSources())) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        mojo.execute();
        assertThat(sources().resolve("Marker.java")).doesNotExist();
        assertThat(testSources()).isDirectory();
        // An option changed.
        Files.writeString(sources().resolve("Marker.java"), "class Marker {}");
        mojo.packageName = "com.example.animals";
        mojo.execute();
        assertThat(sources().resolve("Marker.java")).doesNotExist();
        assertThat(sources().resolve("com/example/animals/model/Pet.java")).exists();
    }

    @Test
    void remoteDocumentsAreCachedAndFetchedAgainWhenAsked() throws Exception {
        var body = new AtomicReference<>("{type: object, properties: {name: {type: string}}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pet.yaml", exchange -> {
            var bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            write("remote.yaml", API.replace("schemas/pet.yaml", "http://127.0.0.1:" + server.getAddress().getPort() + "/pet.yaml"));
            var mojo = mojo("remote.yaml");
            mojo.execute();
            var bundled = resources().resolve("META-INF/openapi-fn/pets/remote/127.0.0.1_" + server.getAddress().getPort() + "/pet.yaml");
            assertThat(bundled).content().doesNotContain("tag");
            var marker = Files.writeString(sources().resolve("Marker.java"), "class Marker {}");
            body.set("{type: object, properties: {name: {type: string}, tag: {type: string}}}");
            // The cached copy is used for a day.
            mojo.execute();
            assertThat(marker).exists();
            // Refreshing fetches the change.
            mojo.refresh = true;
            mojo.execute();
            assertThat(marker).doesNotExist();
            assertThat(bundled).content().contains("tag");
            // Offline builds use the cache, whatever its age.
            server.stop(0);
            mojo.offline = true;
            mojo.execute();
            assertThat(bundled).content().contains("tag");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsInvalidDescriptions() throws Exception {
        write("api.yaml", "openapi: 3.1.0\npaths: {}\n");
        assertThatThrownBy(() -> mojo("api.yaml").execute()).isInstanceOf(MojoFailureException.class)
            .hasMessageStartingWith("Invalid OpenAPI description " + dir.resolve("api.yaml") + ":\n  ")
            .hasMessageContaining("info");
    }

    @Test
    void reportsWhatItCannotWrite() throws Exception {
        write("api.yaml", API.replace("$ref: 'schemas/pet.yaml'", "type: object"));
        var mojo = mojo("api.yaml");
        // The directory for generated sources is taken by a file.
        write("target/generated-sources/openapi-fn", "");
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class).hasMessageStartingWith("Cannot generate code for 'pets': ");
    }

    @Test
    void canBeSkipped() throws Exception {
        var mojo = mojo("missing.yaml");
        mojo.skip = true;
        mojo.execute();
        assertThat(dir.resolve("target")).doesNotExist();
        assertThat(mojo.project.getCompileSourceRoots()).isEmpty();
    }
}
