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

    private static List<String> paths(List<GeneratedFile> files) {
        return files.stream().map(GeneratedFile::path).toList();
    }
}
