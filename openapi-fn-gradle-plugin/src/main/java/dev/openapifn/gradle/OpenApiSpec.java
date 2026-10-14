package dev.openapifn.gradle;

import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * One API description to generate code for.
 *
 * <pre>{@code
 * openapiFn {
 *     specs {
 *         register("petstore") {
 *             spec = file("src/main/openapi/petstore.yaml")
 *             packageName = "com.example.petstore"
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>The name identifies the API at runtime ({@code openapi-fn.specs.<name>.*} properties).
 */
public abstract class OpenApiSpec implements Named {

    private final String name;

    @Inject
    public OpenApiSpec(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    /** The root document; the documents it references are loaded relative to it. */
    public abstract RegularFileProperty getSpec();

    /** The base package: models go to {@code <package>.model}, API types to {@code <package>.api}. */
    public abstract Property<String> getPackageName();

    /** The path controllers are mapped to, if it differs from the path of the first server URL. */
    public abstract Property<String> getBasePath();

    /** Whether to generate API interfaces and controllers (default {@code true}). */
    public abstract Property<Boolean> getServer();

    /** Whether to generate clients (default {@code true}). */
    public abstract Property<Boolean> getClient();

    /** Whether to generate controller tests (default {@code true}; requires {@link #getServer() server}). */
    public abstract Property<Boolean> getTests();

    /**
     * Whether to generate for Spring WebFlux: {@code Mono} and {@code Flux} in interfaces, reactive
     * controllers, {@code WebClient} clients and {@code WebTestClient} tests (default: the extension's
     * {@link OpenApiFnExtension#getReactive() reactive}).
     */
    public abstract Property<Boolean> getReactive();
}
