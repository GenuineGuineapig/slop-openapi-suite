package dev.openapifn.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonPointer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadingTest {

    @TempDir
    Path dir;

    @Test
    void fileSystemLoader() throws IOException {
        var file = Files.writeString(dir.resolve("a.yaml"), "x: 1");
        assertThat(ResourceLoaders.fileSystem().load(file.toUri())).isEqualTo(Result.ok("x: 1"));
        assertThat(ResourceLoaders.fileSystem().load(dir.resolve("missing.yaml").toUri()).failure().orElseThrow())
            .startsWith("Cannot read").contains("NoSuchFileException");
    }

    @Test
    void classpathLoader() {
        var loader = ResourceLoaders.classpath(getClass().getClassLoader());
        assertThat(loader.load(URI.create("classpath:/loading/hello.yaml"))).isEqualTo(Result.ok("hello: world\n"));
        assertThat(loader.load(URI.create("classpath:/loading/missing.yaml")).failure()).contains("Class path resource not found: loading/missing.yaml");
        var failing = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("disk on fire");
                    }
                };
            }
        };
        assertThat(ResourceLoaders.classpath(failing).load(URI.create("classpath:/x")).failure())
            .contains("Cannot read class path resource x: disk on fire");
    }

    @Test
    void inMemoryAndSchemeDispatch() {
        var memory = ResourceLoaders.inMemory(Map.of(URI.create("mem:/a"), "a"));
        assertThat(memory.load(URI.create("mem:/a"))).isEqualTo(Result.ok("a"));
        assertThat(memory.load(URI.create("mem:/b")).failure()).contains("No in-memory document mem:/b");
        var dispatch = ResourceLoaders.bySchemes(Map.of("mem", memory));
        assertThat(dispatch.load(URI.create("mem:/a"))).isEqualTo(Result.ok("a"));
        assertThat(dispatch.load(URI.create("other:/a")).failure()).contains("Unsupported URI scheme in other:/a");
        assertThat(dispatch.load(URI.create("relative")).failure()).contains("Unsupported URI scheme in relative");
        var extended = ResourceLoaders.withSchemes(dispatch, Map.of("x", uri -> Result.ok("x")));
        assertThat(extended.load(URI.create("x:/1"))).isEqualTo(Result.ok("x"));
        assertThat(extended.load(URI.create("mem:/a"))).isEqualTo(Result.ok("a"));
        assertThat(ResourceLoaders.defaults().load(URI.create("classpath:/loading/hello.yaml")).isOk()).isTrue();
    }

    @Test
    void httpLoader() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            var body = "ok: true".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var loader = ResourceLoaders.http(HttpClient.newHttpClient(), Duration.ofSeconds(5));
            assertThat(loader.load(URI.create(base + "/ok"))).isEqualTo(Result.ok("ok: true"));
            assertThat(loader.load(URI.create(base + "/missing")).failure()).contains("Cannot fetch " + base + "/missing: HTTP 404");
        } finally {
            server.stop(0);
        }
        var unreachable = ResourceLoaders.http(HttpClient.newHttpClient(), Duration.ofSeconds(5)).load(URI.create("http://127.0.0.1:1/x"));
        assertThat(unreachable.failure().orElseThrow()).startsWith("Cannot fetch http://127.0.0.1:1/x: ");
    }

    @Test
    void documentLoaderCachesAndAliasesSelf() {
        var calls = new AtomicInteger();
        var documents = Map.of(
            URI.create("mem:/a.yaml"), "$self: https://example.com/api/a.yaml\nx: 1",
            URI.create("mem:/b.yaml"), "y: 2",
            URI.create("mem:/bad.yaml"), "a: [");
        ResourceLoader resources = uri -> {
            calls.incrementAndGet();
            return ResourceLoaders.inMemory(documents).load(uri);
        };
        var loader = new DocumentLoader(resources);
        var a = loader.load(URI.create("mem:/a.yaml#/x")).toOptional().orElseThrow();
        assertThat(loader.load(URI.create("mem:/./a.yaml"))).isEqualTo(Result.ok(a));
        assertThat(calls).hasValue(1);
        assertThat(loader.load(URI.create("https://example.com/api/a.yaml"))).isEqualTo(Result.ok(a));
        assertThat(loader.loaded(URI.create("https://example.com/api/a.yaml#frag"))).contains(a);
        assertThat(loader.loaded(URI.create("mem:/b.yaml"))).isEmpty();
        assertThat(loader.load(URI.create("mem:/b.yaml")).isOk()).isTrue();
        assertThat(loader.load(URI.create("mem:/missing.yaml")).failure().orElseThrow().message()).contains("No in-memory document");
        assertThat(loader.load(URI.create("mem:/bad.yaml")).isErr()).isTrue();
        assertThat(loader.loaded(URI.create("mem:/bad.yaml"))).isEmpty();
        var registered = new Document(URI.create("mem:/c.yaml"), JsonNull.INSTANCE, SourceMap.EMPTY);
        loader.register(registered);
        assertThat(loader.documents().keySet()).containsExactly(URI.create("mem:/a.yaml"), URI.create("https://example.com/api/a.yaml"),
            URI.create("mem:/b.yaml"), URI.create("mem:/c.yaml"));
    }

    @Test
    void documentBaseUri() {
        var plain = DocumentParser.parse(URI.create("mem:/a.yaml"), "x: 1").toOptional().orElseThrow();
        assertThat(plain.baseUri()).isEqualTo(URI.create("mem:/a.yaml"));
        var relative = DocumentParser.parse(URI.create("mem:/dir/a.yaml"), "$self: ../b.yaml#frag").toOptional().orElseThrow();
        assertThat(relative.baseUri()).isEqualTo(URI.create("mem:/b.yaml"));
        var invalid = DocumentParser.parse(URI.create("mem:/a.yaml"), "$self: '%zz'").toOptional().orElseThrow();
        assertThat(invalid.baseUri()).isEqualTo(URI.create("mem:/a.yaml"));
        assertThatThrownBy(() -> new Document(null, JsonNull.INSTANCE, SourceMap.EMPTY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Document(URI.create("a:b"), null, SourceMap.EMPTY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Document(URI.create("a:b"), JsonNull.INSTANCE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void uris() {
        assertThat(Uris.parse("classpath:api/x.yaml")).isEqualTo(Result.ok(URI.create("classpath:/api/x.yaml")));
        assertThat(Uris.parse("classpath:/api/x.yaml")).isEqualTo(Result.ok(URI.create("classpath:/api/x.yaml")));
        assertThat(Uris.parse("#/paths/~1pets~1{id} x/é")).isEqualTo(Result.ok(URI.create("#/paths/~1pets~1%7Bid%7D%20x/%C3%A9")));
        assertThat(Uris.parse("a#b#c").failure().orElseThrow()).startsWith("Invalid URI reference 'a#b#c'");
        assertThat(Uris.resolve(URI.create("file:/a/b.yaml"), "c.yaml#/x")).isEqualTo(Result.ok(URI.create("file:/a/c.yaml#/x")));
        assertThat(Uris.withoutFragment(URI.create("file:/a#x"))).isEqualTo(URI.create("file:/a"));
        var plain = URI.create("file:/a");
        assertThat(Uris.withoutFragment(plain)).isSameAs(plain);
        assertThat(Uris.fragment(URI.create("a#%7Bx%7D"))).isEqualTo("{x}");
        assertThat(Uris.fragment(plain)).isEmpty();
    }

    @Test
    void diagnostics() {
        var diagnostic = Diagnostic.warning("careful", URI.create("mem:/a"), JsonPointer.of("x"));
        assertThat(diagnostic.isError()).isFalse();
        assertThat(diagnostic).hasToString("WARNING mem:/a#/x: careful");
        assertThat(diagnostic.withPosition(Optional.of(new Position(1, 2)))).hasToString("WARNING mem:/a#/x (1:2): careful");
        var root = URI.create("mem:/a");
        var pointer = JsonPointer.ROOT;
        assertThatThrownBy(() -> new Diagnostic(null, "m", root, pointer, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic(Diagnostic.Severity.ERROR, null, root, pointer, Optional.empty()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic(Diagnostic.Severity.ERROR, "m", null, pointer, Optional.empty()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic(Diagnostic.Severity.ERROR, "m", root, null, Optional.empty()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic(Diagnostic.Severity.ERROR, "m", root, pointer, null)).isInstanceOf(NullPointerException.class);
        assertThat(SourceMap.EMPTY.position(JsonPointer.of("a"))).isEmpty();
    }
}
