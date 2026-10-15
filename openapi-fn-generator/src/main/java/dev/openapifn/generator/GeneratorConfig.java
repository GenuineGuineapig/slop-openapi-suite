package dev.openapifn.generator;

import java.util.Objects;
import java.util.Optional;

/**
 * What to generate.
 *
 * @param name the API name: used for the bundled description ({@code META-INF/openapi-fn/<name>}),
 *     runtime lookup and properties ({@code openapi-fn.specs.<name>})
 * @param basePackage the base package; models go to {@code .model}, API types to {@code .api}
 * @param server whether to generate delegate interfaces and controllers
 * @param client whether to generate clients
 * @param tests whether to generate controller tests (requires {@code server})
 * @param basePath the path controllers are mapped to, if it differs from the first server URL path
 * @param reactive whether to generate for Spring WebFlux ({@code Mono}/{@code Flux}, {@code WebClient}
 *     clients, {@code WebTestClient} tests) rather than Spring MVC
 */
public record GeneratorConfig(String name, String basePackage, boolean server, boolean client, boolean tests, Optional<String> basePath,
                              boolean reactive) {

    public GeneratorConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(basePackage, "basePackage");
        Objects.requireNonNull(basePath, "basePath");
    }

    /** Everything for Spring MVC, mapped to the server URL path. */
    public static GeneratorConfig of(String name, String basePackage) {
        return new GeneratorConfig(name, basePackage, true, true, true, Optional.empty(), false);
    }

    public GeneratorConfig withServer(boolean value) {
        return new GeneratorConfig(name, basePackage, value, client, tests, basePath, reactive);
    }

    public GeneratorConfig withClient(boolean value) {
        return new GeneratorConfig(name, basePackage, server, value, tests, basePath, reactive);
    }

    public GeneratorConfig withTests(boolean value) {
        return new GeneratorConfig(name, basePackage, server, client, value, basePath, reactive);
    }

    public GeneratorConfig withBasePath(String value) {
        return new GeneratorConfig(name, basePackage, server, client, tests, Optional.of(value), reactive);
    }

    public GeneratorConfig withReactive(boolean value) {
        return new GeneratorConfig(name, basePackage, server, client, tests, basePath, value);
    }
}
