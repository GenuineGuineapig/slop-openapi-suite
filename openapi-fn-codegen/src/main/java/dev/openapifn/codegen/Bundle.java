package dev.openapifn.codegen;

import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.Document;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies the documents of an API description into {@code META-INF/openapi-fn/<name>/},
 * preserving their relative layout so that relative references keep working, and registers the
 * bundle with a {@code spec.properties} file.
 */
final class Bundle {

    private Bundle() {
    }

    /** The bundle files and the class path location of the root document. */
    record Result(List<GeneratedFile> files, String location) {
    }

    static Result of(String name, URI root, Map<URI, Document> documents, java.util.Optional<String> basePath) {
        var distinct = new IdentityHashMap<Document, URI>();
        documents.values().forEach(document -> distinct.putIfAbsent(document, document.uri()));
        var base = commonDirectory(distinct.values().stream().toList());
        var prefix = "META-INF/openapi-fn/" + name + "/";
        var files = new ArrayList<GeneratedFile>();
        distinct.forEach((document, uri) -> files.add(new GeneratedFile(GeneratedFile.Kind.RESOURCE, prefix + relative(base, uri),
            JsonWriter.pretty(document.root()) + "\n")));
        var location = "classpath:" + prefix + relative(base, root);
        var properties = new StringBuilder("# Registers the API description '" + name + "' with the openapi-fn runtime.\n")
            .append("name=").append(name).append('\n')
            .append("location=").append(location).append('\n');
        basePath.ifPresent(path -> properties.append("base-path=").append(path).append('\n'));
        files.add(new GeneratedFile(GeneratedFile.Kind.RESOURCE, prefix + "spec.properties", properties.toString()));
        return new Result(files, location);
    }

    /** The longest common directory path of {@code uris}. */
    static String commonDirectory(List<URI> uris) {
        var common = directory(uris.getFirst());
        for (var uri : uris) {
            var directory = directory(uri);
            while (!directory.startsWith(common)) {
                common = common.substring(0, common.lastIndexOf('/', common.length() - 2) + 1);
            }
        }
        return common;
    }

    private static String directory(URI uri) {
        var text = uri.toString();
        return text.substring(0, text.lastIndexOf('/') + 1);
    }

    private static String relative(String base, URI uri) {
        return uri.toString().substring(base.length());
    }
}
