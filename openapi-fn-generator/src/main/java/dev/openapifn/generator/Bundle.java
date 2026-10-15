package dev.openapifn.generator;

import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.Document;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Copies the documents of an API description into {@code META-INF/openapi-fn/<name>/} and
 * registers the bundle with a {@code spec.properties} file. Documents next to the root keep their
 * relative layout, so relative references keep working; documents from elsewhere (such as remote
 * references) go to {@code remote/<host>/<path>} and are listed as aliases, so the runtime loads
 * the bundled copies instead of fetching them.
 */
final class Bundle {

    private Bundle() {
    }

    /** The bundle files and the class path location of the root document. */
    record Result(List<GeneratedFile> files, String location) {
    }

    /**
     * A generated controller receiving a webhook or callback.
     *
     * @param method the Java method, which names it in {@code openapi-fn.specs.<api>.receivers.<method>.path}
     * @param operation the operation id
     * @param path the default path
     */
    record Receiver(String method, String operation, String path) {
    }

    static Result of(String name, URI root, Map<URI, Document> documents, java.util.Optional<String> basePath, List<Receiver> receivers) {
        var distinct = new IdentityHashMap<Document, URI>();
        documents.values().forEach(document -> distinct.putIfAbsent(document, document.uri()));
        var base = commonDirectory(distinct.values().stream().filter(uri -> sameOrigin(uri, root)).toList());
        var prefix = "META-INF/openapi-fn/" + name + "/";
        var files = new ArrayList<GeneratedFile>();
        var aliases = new TreeMap<String, String>();
        distinct.forEach((document, uri) -> {
            var path = sameOrigin(uri, root) ? relative(base, uri) : remotePath(uri);
            files.add(new GeneratedFile(GeneratedFile.Kind.RESOURCE, prefix + path, JsonWriter.pretty(document.root()) + "\n"));
            if (!sameOrigin(uri, root)) {
                aliases.put(uri.toString(), "classpath:" + prefix + path);
            }
        });
        var location = "classpath:" + prefix + relative(base, root);
        var properties = new StringBuilder("# Registers the API description '" + name + "' with the openapi-fn runtime.\n")
            .append("name=").append(name).append('\n')
            .append("location=").append(location).append('\n');
        basePath.ifPresent(path -> properties.append("base-path=").append(path).append('\n'));
        var index = 0;
        for (var alias : aliases.entrySet()) {
            properties.append("alias.").append(index).append(".uri=").append(alias.getKey()).append('\n')
                .append("alias.").append(index++).append(".location=").append(alias.getValue()).append('\n');
        }
        receivers.forEach(receiver -> properties.append("receiver.").append(receiver.method()).append(".operation=").append(receiver.operation())
            .append('\n').append("receiver.").append(receiver.method()).append(".path=").append(receiver.path()).append('\n'));
        files.add(new GeneratedFile(GeneratedFile.Kind.RESOURCE, prefix + "spec.properties", properties.toString()));
        return new Result(files, location);
    }

    private static boolean sameOrigin(URI uri, URI root) {
        return Objects.equals(uri.getScheme(), root.getScheme()) && Objects.equals(uri.getRawAuthority(), root.getRawAuthority());
    }

    /** Where a document from another origin is bundled: {@code remote/<host>/<path>} (or {@code remote/<scheme>/...} for URNs). */
    static String remotePath(URI uri) {
        var raw = Objects.requireNonNullElse(uri.getRawPath(), uri.getRawSchemeSpecificPart());
        var path = raw.isEmpty() || raw.endsWith("/") ? raw + "index" : raw;
        var query = uri.getRawQuery() == null ? "" : "_" + Integer.toHexString(uri.getRawQuery().hashCode());
        var origin = Objects.requireNonNullElse(uri.getRawAuthority(), uri.getScheme()).replace(':', '_');
        return "remote/" + origin + (path.startsWith("/") ? "" : "/") + path + query;
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
