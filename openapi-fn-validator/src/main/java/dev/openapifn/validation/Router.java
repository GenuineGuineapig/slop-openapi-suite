package dev.openapifn.validation;

import dev.openapifn.http.PathTemplate;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.model.Server;
import dev.openapifn.parse.OpenApiDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps request paths and methods to operations. Server URL paths (including templated ones such
 * as {@code /api/{version}}) are stripped first; concrete paths win over templated ones.
 */
public final class Router {

    /** Scheme and authority of an absolute URL; the scheme may be a variable ({@code {scheme}://host}). */
    private static final Pattern ABSOLUTE = Pattern.compile("^(?:[a-zA-Z][a-zA-Z0-9+.-]*|\\{[^}/]+})://[^/]*");
    private static final Pattern LEADING_VARIABLE = Pattern.compile("^\\{([^}/]+)}");

    private record Entry(PathTemplate template, List<PathTemplate> prefixes, Map<String, ApiOperation> operations) {
    }

    private final List<Entry> entries;
    private final boolean requireServerPrefix;
    private final boolean headFallsBackToGet;

    /**
     * A router that also accepts {@code basePaths} (such as a configured mount point) as prefixes
     * of every path, in addition to the server URL paths.
     */
    public Router(OpenApiDocument api, boolean requireServerPrefix, boolean headFallsBackToGet, List<String> basePaths) {
        this.requireServerPrefix = requireServerPrefix;
        this.headFallsBackToGet = headFallsBackToGet;
        var byPath = new LinkedHashMap<String, Map<String, ApiOperation>>();
        api.operations().forEach(op -> byPath.computeIfAbsent(op.path(), path -> new LinkedHashMap<>()).put(op.method(), op));
        var list = new ArrayList<Entry>();
        // Paths with the same prefixes share them, so that a request strips each set of prefixes once (see find).
        var shared = new HashMap<List<String>, List<PathTemplate>>();
        byPath.forEach((path, operations) -> {
            var servers = operations.values().iterator().next().pathItem().servers();
            var paths = new ArrayList<>(basePaths);
            (servers.isEmpty() ? api.model().servers() : servers).stream().map(Router::prefix).forEach(paths::add);
            var prefixes = shared.computeIfAbsent(paths.stream().distinct().toList(), distinct -> distinct.stream().map(PathTemplate::compile).toList());
            list.add(new Entry(PathTemplate.compile(path), prefixes, operations));
        });
        list.sort(Comparator.comparing(Entry::template, PathTemplate::compareSpecificity));
        this.entries = List.copyOf(list);
    }

    /** The path part of a server URL, with variables kept as template expressions. */
    static String prefix(Server server) {
        var url = server.url();
        // A leading variable whose default is an absolute URL stands for the origin ({base}/v1).
        var leading = LEADING_VARIABLE.matcher(url);
        if (leading.lookingAt() && Optional.ofNullable(server.variables().get(leading.group(1)))
            .filter(variable -> ABSOLUTE.matcher(variable.defaultValue()).lookingAt()).isPresent()) {
            url = url.substring(leading.end());
        }
        var path = ABSOLUTE.matcher(url).replaceFirst("");
        var query = path.indexOf('?');
        path = query >= 0 ? path.substring(0, query) : path;
        return path.startsWith("/") ? path : "/" + path;
    }

    public Route route(String method, String path) {
        var found = find(path, false);
        if (found.isEmpty() && !requireServerPrefix) {
            found = find(path, true);
        }
        if (found.isEmpty()) {
            return new Route.NotFound();
        }
        var entry = found.get().getKey();
        var values = found.get().getValue();
        var operation = Optional.ofNullable(entry.operations().get(method))
            .or(() -> headFallsBackToGet && method.equals("HEAD") ? Optional.ofNullable(entry.operations().get("GET")) : Optional.empty());
        return operation.<Route>map(op -> new Route.Found(new OperationMatch(op, values)))
            .orElseGet(() -> new Route.MethodNotAllowed(entry.template().template(), new LinkedHashSet<>(entry.operations().keySet())));
    }

    private Optional<Map.Entry<Entry, Map<String, String>>> find(String path, boolean ignorePrefixes) {
        var stripped = new IdentityHashMap<List<PathTemplate>, List<String>>();
        for (var entry : entries) {
            var candidates = ignorePrefixes ? List.of(path)
                : stripped.computeIfAbsent(entry.prefixes(), prefixes -> prefixes.stream().flatMap(p -> p.stripPrefix(path).stream()).toList());
            for (var candidate : candidates) {
                var values = entry.template().match(candidate);
                if (values.isPresent()) {
                    return Optional.of(Map.entry(entry, values.get()));
                }
            }
        }
        return Optional.empty();
    }
}
