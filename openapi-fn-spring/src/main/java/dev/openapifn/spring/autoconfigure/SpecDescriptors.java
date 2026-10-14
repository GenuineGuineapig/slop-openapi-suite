package dev.openapifn.spring.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Finds the API descriptions registered by generated code: each
 * {@code META-INF/openapi-fn/<name>/spec.properties} names a {@code location}, optionally a
 * {@code base-path}, and the bundled copies of remote documents ({@code alias.<n>.uri} and
 * {@code alias.<n>.location}).
 */
final class SpecDescriptors {

    static final String PATTERN = "classpath*:META-INF/openapi-fn/*/spec.properties";

    private SpecDescriptors() {
    }

    static Map<String, OpenApiFnProperties.Spec> discover(ClassLoader classLoader) {
        var specs = new LinkedHashMap<String, OpenApiFnProperties.Spec>();
        try {
            for (var resource : new PathMatchingResourcePatternResolver(classLoader).getResources(PATTERN)) {
                var properties = new Properties();
                try (var in = resource.getInputStream()) {
                    properties.load(in);
                }
                var aliases = new LinkedHashMap<String, String>();
                for (int i = 0; properties.containsKey("alias." + i + ".uri"); i++) {
                    aliases.put(properties.getProperty("alias." + i + ".uri"), properties.getProperty("alias." + i + ".location"));
                }
                var receivers = new LinkedHashMap<String, OpenApiFnProperties.Receiver>();
                properties.stringPropertyNames().stream().filter(key -> key.startsWith("receiver.") && key.endsWith(".operation")).sorted()
                    .map(key -> key.substring("receiver.".length(), key.length() - ".operation".length()))
                    .forEach(method -> receivers.put(method, new OpenApiFnProperties.Receiver(properties.getProperty("receiver." + method + ".operation"),
                        properties.getProperty("receiver." + method + ".path"))));
                specs.put(properties.getProperty("name"), new OpenApiFnProperties.Spec(properties.getProperty("location"),
                    properties.getProperty("base-path"), aliases, receivers));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return specs;
    }

    /** Discovered descriptions overlaid with configured ones (configured values win when set). */
    static Map<String, OpenApiFnProperties.Spec> merge(Map<String, OpenApiFnProperties.Spec> discovered, Map<String, OpenApiFnProperties.Spec> configured) {
        var merged = new LinkedHashMap<>(discovered);
        configured.forEach((name, spec) -> merged.merge(name, spec, (found, override) -> {
            var aliases = new LinkedHashMap<>(found.aliases());
            aliases.putAll(override.aliases());
            var receivers = new LinkedHashMap<>(found.receivers());
            override.receivers().forEach((method, receiver) -> receivers.merge(method, receiver, (generated, set) -> new OpenApiFnProperties.Receiver(
                set.operation() != null ? set.operation() : generated.operation(), set.path() != null ? set.path() : generated.path())));
            return new OpenApiFnProperties.Spec(override.location() != null ? override.location() : found.location(),
                override.basePath() != null ? override.basePath() : found.basePath(), aliases, receivers);
        }));
        return merged;
    }
}
