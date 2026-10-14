package dev.openapifn.spring;

import dev.openapifn.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The runtimes of all API descriptions of an application, by name. */
public final class OpenApiRuntimes {

    private final Map<String, OpenApiRuntime> runtimes;

    public OpenApiRuntimes(List<OpenApiRuntime> runtimes) {
        var byName = new LinkedHashMap<String, OpenApiRuntime>();
        runtimes.forEach(runtime -> byName.put(runtime.name(), runtime));
        this.runtimes = Map.copyOf(byName);
    }

    /** The runtime called {@code name}. */
    public OpenApiRuntime get(String name) {
        var runtime = runtimes.get(name);
        if (runtime == null) {
            throw new IllegalArgumentException("No OpenAPI description named '" + name + "'; known: " + runtimes.keySet());
        }
        return runtime;
    }

    public Map<String, OpenApiRuntime> all() {
        return runtimes;
    }

    /** The first runtime with a path or receiver matching {@code request} (whatever the method). */
    public Optional<OpenApiRuntime> routing(HttpRequest request) {
        return runtimes.values().stream().filter(runtime -> runtime.routes(request)).findFirst();
    }
}
