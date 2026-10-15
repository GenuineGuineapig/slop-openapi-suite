package dev.openapifn.gradle;

import java.time.Duration;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/** The {@code openapiFn} extension: the API descriptions to generate code for. */
public abstract class OpenApiFnExtension {

    private final NamedDomainObjectContainer<OpenApiSpec> specs;

    @Inject
    public OpenApiFnExtension(ObjectFactory objects) {
        this.specs = objects.domainObjectContainer(OpenApiSpec.class);
    }

    public NamedDomainObjectContainer<OpenApiSpec> getSpecs() {
        return specs;
    }

    public void specs(Action<? super NamedDomainObjectContainer<OpenApiSpec>> action) {
        action.execute(specs);
    }

    /**
     * Whether to add the runtime ({@code openapi-fn-spring}, or {@code openapi-fn-spring-webflux} when
     * {@link #getReactive() reactive}) and test ({@code openapi-fn-spring-test} and the Spring Boot
     * MVC or WebFlux test starter) dependencies the generated code needs (default {@code true}).
     */
    public abstract Property<Boolean> getAddDependencies();

    /**
     * Whether the application uses Spring WebFlux rather than Spring MVC (default {@code false}): the
     * default of each spec's {@link OpenApiSpec#getReactive() reactive} setting, and which
     * dependencies are added.
     */
    public abstract Property<Boolean> getReactive();

    /**
     * How long remote documents descriptions reference are used from the cache in the Gradle user
     * home before they are fetched again (default 24 hours). {@code --refresh-dependencies} fetches
     * them now; {@code --offline} uses the cached copies, whatever their age.
     */
    public abstract Property<Duration> getRemoteMaxAge();
}
