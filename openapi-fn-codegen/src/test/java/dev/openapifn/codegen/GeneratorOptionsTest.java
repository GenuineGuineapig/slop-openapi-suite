package dev.openapifn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorOptionsTest {

    @Test
    void derivesBasePathsFromServerUrls() {
        assertThat(Generator.basePath("https://h")).isEqualTo("/");
        assertThat(Generator.basePath("/")).isEqualTo("/");
        assertThat(Generator.basePath("https://h:8080/api/")).isEqualTo("/api");
        assertThat(Generator.basePath("v2/")).isEqualTo("/v2");
        assertThat(Generator.basePath("/api?x=1")).isEqualTo("/api");
    }

    @Test
    void generatesOnlyWhatIsConfigured() {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var clientOnly = paths(Generator.generate(api, GeneratorConfig.of("shop", "com.example.shop").withServer(false).withBasePath("/base")));
        assertThat(clientOnly).anyMatch(path -> path.endsWith("PetsClient.java")).noneMatch(path -> path.endsWith("Controller.java"))
            .noneMatch(path -> path.endsWith("ControllerTest.java")).noneMatch(path -> path.endsWith("PetsApi.java"));
        var serverOnly = Generator.generate(api, GeneratorConfig.of("shop", "com.example.shop").withClient(false).withTests(false));
        assertThat(paths(serverOnly)).anyMatch(path -> path.endsWith("PetsController.java")).noneMatch(path -> path.endsWith("Client.java"))
            .noneMatch(path -> path.endsWith("ControllerTest.java")).noneMatch(path -> path.endsWith("ShopClients.java"));
        var properties = Generator.generate(api, GeneratorConfig.of("shop", "com.example.shop").withBasePath("/base")).stream()
            .filter(file -> file.path().endsWith("spec.properties")).findFirst().orElseThrow();
        assertThat(properties.kind()).isEqualTo(GeneratedFile.Kind.RESOURCE);
        assertThat(properties.content()).contains("name=shop\n", "base-path=/base\n");
    }

    @Test
    void bundlesDocumentsBelowTheirCommonDirectory() {
        assertThat(Bundle.commonDirectory(List.of(java.net.URI.create("file:/a/b/c/x.yaml"), java.net.URI.create("file:/a/b/d/y.yaml"),
            java.net.URI.create("file:/a/b/c/e/z.yaml")))).isEqualTo("file:/a/b/");
    }

    @Test
    void bundlesRemoteDocumentsByOrigin() {
        assertThat(Bundle.remotePath(java.net.URI.create("https://schemas.example.com:8443/common/point.yaml"))).isEqualTo(
            "remote/schemas.example.com_8443/common/point.yaml");
        assertThat(Bundle.remotePath(java.net.URI.create("https://example.com"))).isEqualTo("remote/example.com/index");
        assertThat(Bundle.remotePath(java.net.URI.create("https://example.com/specs/"))).isEqualTo("remote/example.com/specs/index");
        assertThat(Bundle.remotePath(java.net.URI.create("https://example.com/spec?v=2"))).isEqualTo("remote/example.com/spec_" + Integer.toHexString("v=2".hashCode()));
        assertThat(Bundle.remotePath(java.net.URI.create("urn:example:pets"))).isEqualTo("remote/urn/example:pets");
    }

    @Test
    void bundlesDocumentsOfOtherHostsAsRemote() {
        var root = java.net.URI.create("https://api.example.com/v1/openapi.yaml");
        var documents = new java.util.LinkedHashMap<java.net.URI, dev.openapifn.load.Document>();
        for (var uri : List.of(root, java.net.URI.create("https://api.example.com/v1/schemas/pet.yaml"), java.net.URI.create("https://schemas.example.com/x.yaml"))) {
            documents.put(uri, dev.openapifn.load.DocumentParser.parse(uri, "{}").orElseThrow(e -> new AssertionError(e.toString())));
        }
        var bundle = Bundle.of("api", root, documents, java.util.Optional.empty(), List.of(new Bundle.Receiver("hook", "POST hook", "/webhooks/hook")));
        assertThat(bundle.location()).isEqualTo("classpath:META-INF/openapi-fn/api/openapi.yaml");
        assertThat(paths(bundle.files())).containsExactlyInAnyOrder("META-INF/openapi-fn/api/openapi.yaml", "META-INF/openapi-fn/api/schemas/pet.yaml",
            "META-INF/openapi-fn/api/remote/schemas.example.com/x.yaml", "META-INF/openapi-fn/api/spec.properties");
        assertThat(bundle.files().getLast().content()).endsWith("""
            alias.0.uri=https://schemas.example.com/x.yaml
            alias.0.location=classpath:META-INF/openapi-fn/api/remote/schemas.example.com/x.yaml
            receiver.hook.operation=POST hook
            receiver.hook.path=/webhooks/hook
            """);
    }

    private static List<String> paths(List<GeneratedFile> files) {
        return files.stream().map(GeneratedFile::path).toList();
    }
}
