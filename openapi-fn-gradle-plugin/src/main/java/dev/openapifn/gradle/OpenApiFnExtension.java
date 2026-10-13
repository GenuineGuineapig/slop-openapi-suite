package dev.openapifn.gradle;

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
     * Whether to add the runtime ({@code openapi-fn-spring}) and test ({@code openapi-fn-spring-test},
     * {@code spring-boot-starter-webmvc-test}) dependencies the generated code needs (default {@code true}).
     */
    public abstract Property<Boolean> getAddDependencies();
}
