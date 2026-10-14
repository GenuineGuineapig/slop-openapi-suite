package dev.openapifn.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiFnPluginTest {

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

    private Project project(boolean java) {
        var project = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        if (java) {
            project.getPluginManager().apply("java");
        }
        project.getPluginManager().apply("dev.openapifn");
        return project;
    }

    private Path write(String path, String content) throws Exception {
        var file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static OpenApiFnExtension extension(Project project) {
        return project.getExtensions().getByType(OpenApiFnExtension.class);
    }

    private GenerateOpenApi register(Project project, String name, String spec) {
        extension(project).specs(specs -> specs.register(name, s -> {
            s.getSpec().set(new File(dir.toFile(), spec));
            s.getPackageName().set("com.example.pets");
        }));
        return (GenerateOpenApi) project.getTasks().getByName("generate" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "OpenApi");
    }

    @Test
    void registersATaskPerSpecAndWiresItsOutputsIntoTheBuild() throws Exception {
        write("src/main/openapi/api.yaml", API);
        var project = project(true);
        var task = register(project, "pets", "src/main/openapi/api.yaml");
        assertThat(task.getGroup()).isEqualTo(OpenApiFnPlugin.GROUP);
        assertThat(task.getDescription()).isEqualTo("Generates code from the OpenAPI description 'pets'");
        assertThat(task.getApiName().get()).isEqualTo("pets");
        assertThat(task.getServer().get()).isTrue();
        assertThat(task.getClient().get()).isTrue();
        assertThat(task.getTests().get()).isTrue();
        assertThat(task.getReactive().get()).isFalse();
        assertThat(task.getBasePath().isPresent()).isFalse();
        assertThat(task.getReferences().getFiles()).containsExactly(dir.resolve("src/main/openapi").toFile());
        var output = project.getLayout().getBuildDirectory().dir("generated/openapi-fn/pets").get().getAsFile().toPath();
        assertThat(task.getSourcesDir().get().getAsFile().toPath()).isEqualTo(output.resolve("java"));
        assertThat(task.getTestSourcesDir().get().getAsFile().toPath()).isEqualTo(output.resolve("test-java"));
        assertThat(task.getResourcesDir().get().getAsFile().toPath()).isEqualTo(output.resolve("resources"));

        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        assertThat(sourceSets.getByName("main").getJava().getSrcDirs()).contains(output.resolve("java").toFile());
        assertThat(sourceSets.getByName("main").getResources().getSrcDirs()).contains(output.resolve("resources").toFile());
        assertThat(sourceSets.getByName("test").getJava().getSrcDirs()).contains(output.resolve("test-java").toFile());
        assertThat(dependencies(project.getTasks().getByName("compileJava"))).contains(task);
        assertThat(dependencies(project.getTasks().getByName("processResources"))).contains(task);
        assertThat(dependencies(project.getTasks().getByName("compileTestJava"))).contains(task);
        assertThat(dependencies(project.getTasks().getByName(OpenApiFnPlugin.GENERATE_ALL))).containsExactly((Task) task);
        assertThat(project.getTasks().getByName(OpenApiFnPlugin.GENERATE_ALL).getGroup()).isEqualTo(OpenApiFnPlugin.GROUP);

        assertThat(coordinates(project, "implementation")).containsExactly("dev.openapifn:openapi-fn-spring:" + OpenApiFnPlugin.version());
        assertThat(coordinates(project, "testImplementation")).containsExactly("dev.openapifn:openapi-fn-spring-test:" + OpenApiFnPlugin.version(),
            "org.springframework.boot:spring-boot-starter-webmvc-test:null");
        assertThat(OpenApiFnPlugin.version()).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void reactiveApplicationsGetWebFluxCode() throws Exception {
        write("api.yaml", API.replace("$ref: 'schemas/pet.yaml'", "type: object"));
        var project = project(true);
        extension(project).getReactive().set(true);
        var task = register(project, "pets", "api.yaml");
        assertThat(task.getReactive().get()).isTrue();
        assertThat(coordinates(project, "implementation")).containsExactly("dev.openapifn:openapi-fn-spring-webflux:" + OpenApiFnPlugin.version());
        assertThat(coordinates(project, "testImplementation")).containsExactly("dev.openapifn:openapi-fn-spring-test:" + OpenApiFnPlugin.version(),
            "org.springframework.boot:spring-boot-starter-webflux-test:null");
        task.generate();
        assertThat(task.getSourcesDir().get().getAsFile().toPath().resolve("com/example/pets/api/DefaultClient.java")).content()
            .contains("OpenApiWebClient");
        extension(project).getSpecs().getByName("pets").getReactive().set(false);
        assertThat(task.getReactive().get()).isFalse();
    }

    @Test
    void dependenciesAreOptional() {
        var project = project(true);
        extension(project).getAddDependencies().set(false);
        assertThat(coordinates(project, "implementation")).isEmpty();
        assertThat(coordinates(project, "testImplementation")).isEmpty();
    }

    @Test
    void sourceSetsAreWiredOnceJavaIsApplied() throws Exception {
        write("api.yaml", API);
        var project = project(false);
        var task = register(project, "pets", "api.yaml");
        assertThat(project.getExtensions().findByType(SourceSetContainer.class)).isNull();
        project.getPluginManager().apply("java");
        var main = project.getExtensions().getByType(SourceSetContainer.class).getByName("main");
        assertThat(main.getJava().getSrcDirs()).contains(task.getSourcesDir().get().getAsFile());
    }

    @Test
    void generatesSourcesTestsAndResources() throws Exception {
        write("src/main/openapi/api.yaml", API);
        write("src/main/openapi/schemas/pet.yaml", "{type: object, required: [name], properties: {name: {type: string}}}\n");
        var project = project(true);
        var task = register(project, "pets", "src/main/openapi/api.yaml");
        var stale = task.getSourcesDir().get().getAsFile().toPath().resolve("Stale.java");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "class Stale {}");
        task.generate();
        var sources = task.getSourcesDir().get().getAsFile().toPath();
        assertThat(sources.resolve("com/example/pets/model/Pet.java")).exists();
        assertThat(sources.resolve("com/example/pets/api/DefaultController.java")).exists();
        assertThat(sources.resolve("com/example/pets/api/DefaultClient.java")).exists();
        assertThat(stale).doesNotExist();
        assertThat(task.getTestSourcesDir().get().getAsFile().toPath().resolve("com/example/pets/api/DefaultControllerTest.java")).exists();
        assertThat(task.getResourcesDir().get().getAsFile().toPath().resolve("META-INF/openapi-fn/pets/spec.properties")).content()
            .contains("name=pets").doesNotContain("base-path");
    }

    @Test
    void honoursTheToggles() throws Exception {
        write("api.yaml", API.replace("$ref: 'schemas/pet.yaml'", "type: object") + "x-unknown: 1\njsonSchemaDialect: https://example.com/custom-dialect\n");
        var project = project(true);
        var task = register(project, "pets", "api.yaml");
        extension(project).getSpecs().getByName("pets", spec -> {
            spec.getBasePath().set("/v2");
            spec.getClient().set(false);
            spec.getTests().set(false);
        });
        task.generate();
        assertThat(task.getSourcesDir().get().getAsFile().toPath().resolve("com/example/pets/api/DefaultClient.java")).doesNotExist();
        assertThat(task.getTestSourcesDir().get().getAsFile().toPath().resolve("com/example/pets/api/DefaultControllerTest.java")).doesNotExist();
        assertThat(task.getResourcesDir().get().getAsFile().toPath().resolve("META-INF/openapi-fn/pets/spec.properties")).content()
            .contains("base-path=/v2");
        extension(project).getSpecs().getByName("pets").getServer().set(false);
        task.generate();
        assertThat(task.getSourcesDir().get().getAsFile().toPath().resolve("com/example/pets/api/DefaultController.java")).doesNotExist();
    }

    @Test
    void remoteDocumentsAreFingerprinted() throws Exception {
        var body = new java.util.concurrent.atomic.AtomicReference<>("{type: object, properties: {name: {type: string}}}");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pet.yaml", exchange -> {
            var bytes = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/pet.yaml";
            write("remote.yaml", API.replace("schemas/pet.yaml", remote));
            var project = project(true);
            var task = register(project, "pets", "remote.yaml");
            var fingerprint = task.getRemoteFingerprint().get();
            assertThat(fingerprint).isNotEmpty().isEqualTo(GenerateOpenApi.fingerprint(dir.resolve("remote.yaml")));
            body.set("{type: object, properties: {name: {type: string}, tag: {type: string}}}");
            assertThat(GenerateOpenApi.fingerprint(dir.resolve("remote.yaml"))).isNotEqualTo(fingerprint);
            task.generate();
            assertThat(task.getResourcesDir().get().getAsFile().toPath().resolve("META-INF/openapi-fn/pets/remote/127.0.0.1_"
                + server.getAddress().getPort() + "/pet.yaml")).exists();
            write("local.yaml", API.replace("$ref: 'schemas/pet.yaml'", "type: object"));
            assertThat(GenerateOpenApi.fingerprint(dir.resolve("local.yaml"))).isEmpty();
            assertThat(GenerateOpenApi.fingerprint(dir.resolve("missing.yaml"))).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsInvalidDescriptions() throws Exception {
        write("api.yaml", "openapi: 3.1.0\npaths: {}\n");
        var project = project(true);
        var task = register(project, "broken", "api.yaml");
        assertThatThrownBy(task::generate).isInstanceOf(GradleException.class)
            .hasMessageStartingWith("Invalid OpenAPI description " + dir.resolve("api.yaml") + ":\n  ")
            .hasMessageContaining("info");
    }

    private static java.util.Set<Task> dependencies(Task task) {
        return java.util.Set.copyOf(task.getTaskDependencies().getDependencies(task));
    }

    private static java.util.List<String> coordinates(Project project, String configuration) {
        return project.getConfigurations().getByName(configuration).getDependencies().stream()
            .map(dependency -> dependency.getGroup() + ":" + dependency.getName() + ":" + versionOf(dependency)).toList();
    }

    private static String versionOf(Dependency dependency) {
        return String.valueOf(dependency.getVersion());
    }
}
