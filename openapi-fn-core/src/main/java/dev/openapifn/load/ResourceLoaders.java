package dev.openapifn.load;

import dev.openapifn.fn.Result;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Standard {@link ResourceLoader}s. */
public final class ResourceLoaders {

    private ResourceLoaders() {
    }

    /** File system and class path loading; remote retrieval is deliberately not included. */
    public static ResourceLoader defaults() {
        return bySchemes(Map.of("file", fileSystem(), Uris.CLASSPATH_SCHEME, classpath(ResourceLoaders.class.getClassLoader())));
    }

    /** Loads {@code file:} URIs. */
    public static ResourceLoader fileSystem() {
        return uri -> Result.attempt(() -> Files.readString(Path.of(uri), StandardCharsets.UTF_8),
            e -> "Cannot read " + uri + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
    }

    /** Loads {@code classpath:/path} URIs from {@code classLoader}. */
    public static ResourceLoader classpath(ClassLoader classLoader) {
        return uri -> {
            var path = uri.getPath().substring(1);
            try (var in = classLoader.getResourceAsStream(path)) {
                return in == null
                    ? Result.err("Class path resource not found: " + path)
                    : Result.ok(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Result.err("Cannot read class path resource " + path + ": " + e.getMessage());
            }
        };
    }

    /** Serves fixed content, keyed by absolute URI. */
    public static ResourceLoader inMemory(Map<URI, String> documents) {
        var copy = Map.copyOf(documents);
        return uri -> Result.fromOptional(java.util.Optional.ofNullable(copy.get(uri)), () -> "No in-memory document " + uri);
    }

    /** Fetches {@code http:} and {@code https:} URIs with a GET request. */
    public static ResourceLoader http(HttpClient client, Duration timeout) {
        return uri -> Result.<HttpResponse<String>, String>attempt(
                () -> client.send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                e -> "Cannot fetch " + uri + ": " + e.getClass().getSimpleName() + " " + e.getMessage())
            .filter(response -> response.statusCode() / 100 == 2, response -> "Cannot fetch " + uri + ": HTTP " + response.statusCode())
            .map(HttpResponse::body);
    }

    /** Dispatches on the URI scheme. */
    public static ResourceLoader bySchemes(Map<String, ResourceLoader> loaders) {
        var copy = Map.copyOf(loaders);
        return uri -> {
            var loader = copy.get(String.valueOf(uri.getScheme()));
            return loader == null ? Result.err("Unsupported URI scheme in " + uri) : loader.load(uri);
        };
    }

    /** {@code primary} with additional schemes from {@code extra} (which take precedence). */
    public static ResourceLoader withSchemes(ResourceLoader primary, Map<String, ResourceLoader> extra) {
        var copy = new HashMap<>(extra);
        return uri -> copy.getOrDefault(String.valueOf(uri.getScheme()), primary).load(uri);
    }
}
