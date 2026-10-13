package dev.openapifn.spring.autoconfigure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Finds the API descriptions registered by generated code: each
 * {@code META-INF/openapi-fn/<name>/spec.properties} names a {@code location} and optionally a
 * {@code base-path}.
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
                specs.put(properties.getProperty("name"), new OpenApiFnProperties.Spec(properties.getProperty("location"),
                    properties.getProperty("base-path")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return specs;
    }

    /** Discovered descriptions overlaid with configured ones (configured values win when set). */
    static Map<String, OpenApiFnProperties.Spec> merge(Map<String, OpenApiFnProperties.Spec> discovered, Map<String, OpenApiFnProperties.Spec> configured) {
        var merged = new LinkedHashMap<>(discovered);
        configured.forEach((name, spec) -> merged.merge(name, spec, (found, override) -> new OpenApiFnProperties.Spec(
            override.location() != null ? override.location() : found.location(),
            override.basePath() != null ? override.basePath() : found.basePath())));
        return merged;
    }
}
