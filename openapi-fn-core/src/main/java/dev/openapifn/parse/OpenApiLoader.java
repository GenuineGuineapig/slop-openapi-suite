package dev.openapifn.parse;

import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.Document;
import dev.openapifn.load.DocumentLoader;
import dev.openapifn.load.DocumentParser;
import dev.openapifn.load.ResourceLoader;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.load.Uris;
import dev.openapifn.model.SpecVersion;
import dev.openapifn.schema.Dialect;
import dev.openapifn.schema.SchemaRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Entry point for loading OpenAPI descriptions that may span several documents.
 *
 * <pre>{@code
 * Result<OpenApiDocument, List<Diagnostic>> api = OpenApiLoader.defaults().load("classpath:openapi/api.yaml");
 * }</pre>
 *
 * @param resources how documents are retrieved
 */
public record OpenApiLoader(ResourceLoader resources) {

    public OpenApiLoader {
        Objects.requireNonNull(resources, "resources");
    }

    /** File system and class path loading. */
    public static OpenApiLoader defaults() {
        return new OpenApiLoader(ResourceLoaders.defaults());
    }

    /**
     * Loads from a location: {@code classpath:path}, any absolute URI, or a file system path.
     */
    public Result<OpenApiDocument, List<Diagnostic>> load(String location) {
        if (location.startsWith(Uris.CLASSPATH_SCHEME + ":") || location.matches("^[a-zA-Z][a-zA-Z0-9+.-]+://.*")) {
            return Uris.parse(location)
                .mapError(message -> List.of(Diagnostic.error(message, URI.create("about:invalid"), dev.openapifn.json.JsonPointer.ROOT)))
                .flatMap(this::load);
        }
        return load(Path.of(location));
    }

    public Result<OpenApiDocument, List<Diagnostic>> load(Path path) {
        return load(path.toAbsolutePath().normalize().toUri());
    }

    public Result<OpenApiDocument, List<Diagnostic>> load(URI uri) {
        var loader = new DocumentLoader(resources);
        return loader.load(uri).mapError(List::of).flatMap(document -> interpret(loader, document.uri()));
    }

    /** Parses {@code content} as the root document at {@code uri}; references are loaded through {@link #resources()}. */
    public Result<OpenApiDocument, List<Diagnostic>> parse(URI uri, String content) {
        var loader = new DocumentLoader(resources);
        return DocumentParser.parse(uri, content).mapError(List::of).flatMap(document -> {
            loader.register(document);
            return interpret(loader, uri);
        });
    }

    private static Result<OpenApiDocument, List<Diagnostic>> interpret(DocumentLoader loader, URI uri) {
        var document = loader.load(uri).toOptional().orElseThrow();
        var diagnostics = new ArrayList<Diagnostic>();
        if (Swagger2Converter.applies(document.root())) {
            // Swagger / OpenAPI 2.0 is converted to 3.0 first; everything else then works on 3.x.
            var bundled = Swagger2Bundler.bundle((JsonObject) document.root(), document.uri(),
                location -> loader.load(location).toOptional().map(Document::root));
            var conversion = Swagger2Converter.convert(bundled);
            document = new Document(document.uri(), conversion.root(), document.sourceMap());
            loader.register(document);
            var converted = document;
            conversion.warnings().forEach(warning -> diagnostics.add(Diagnostic.warning(warning, converted.uri(), JsonPointer.ROOT)));
        }
        var parser = new OpenApiParser(loader);
        var model = parser.parse(document);
        diagnostics.addAll(parser.diagnostics());
        if (model.isEmpty()) {
            return Result.err(List.copyOf(diagnostics));
        }
        var dialect = model.get().version() == SpecVersion.V3_0 ? Dialect.OAS_3_0
            : model.get().jsonSchemaDialect().flatMap(Dialect::fromUri).orElse(Dialect.DRAFT_2020_12);
        var registry = SchemaRegistry.build(parser.schemas(), loader, dialect);
        diagnostics.addAll(registry.diagnostics());
        if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
            return Result.err(List.copyOf(diagnostics));
        }
        return Result.ok(new OpenApiDocument(uri, model.get(), registry.registry(), diagnostics));
    }
}
