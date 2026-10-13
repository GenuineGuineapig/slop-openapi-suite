package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoaders;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsFromPathsAndLocations() throws IOException {
        Files.writeString(dir.resolve("api.yaml"), "openapi: 3.1.0\ninfo: {title: t, version: v}\npaths: {}\n");
        var loader = OpenApiLoader.defaults();
        assertThat(loader.load(dir.resolve("api.yaml")).isOk()).isTrue();
        assertThat(loader.load(dir.resolve("api.yaml").toString()).isOk()).isTrue();
        assertThat(loader.load(dir.resolve("api.yaml").toUri().toString().replace("file:", "file://")).isOk()).isTrue();
        assertThat(loader.load(dir.resolve("missing.yaml")).failure().orElseThrow()).singleElement()
            .extracting(Diagnostic::message).asString().contains("NoSuchFileException");
        assertThat(loader.load("classpath:petstore/openapi.yaml").isOk()).isTrue();
        assertThat(loader.load("https://example.com/%zz").failure().orElseThrow()).singleElement()
            .extracting(Diagnostic::message).asString().startsWith("Invalid URI reference");
        assertThat(loader.load("https://example.com/api.yaml").failure().orElseThrow()).singleElement()
            .extracting(Diagnostic::message).asString().contains("Unsupported URI scheme");
    }

    @Test
    void parsesInMemoryContent() {
        var loader = new OpenApiLoader(ResourceLoaders.inMemory(Map.of()));
        assertThat(loader.parse(URI.create("mem:/a.yaml"), "openapi: 3.1.0\ninfo: {title: t, version: v}\npaths: {}").isOk()).isTrue();
        assertThat(loader.parse(URI.create("mem:/a.yaml"), "a: [").failure().orElseThrow()).singleElement()
            .extracting(Diagnostic::message).asString().startsWith("Invalid YAML");
        assertThatThrownBy(() -> new OpenApiLoader(null)).isInstanceOf(NullPointerException.class);
    }
}
