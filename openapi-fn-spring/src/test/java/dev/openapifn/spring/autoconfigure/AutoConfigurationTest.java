package dev.openapifn.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.spring.OpenApiRuntimes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenApiFnAutoConfiguration.class));

    @Test
    void discoversGeneratedSpecsWithoutAWebApplication() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(OpenApiRuntimes.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context.getBean(OpenApiRuntimes.class).get("pets").api().model().info().title()).isEqualTo("Pets");
        });
    }

    @Test
    void registersTheFilterInWebApplications() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenApiFnAutoConfiguration.class))
            .withPropertyValues("openapi-fn.validation.filter-order=42")
            .run(context -> assertThat(context.getBean("openApiValidationFilter", FilterRegistrationBean.class).getOrder()).isEqualTo(42));
    }

    @Test
    void configuredSpecsOverrideAndExtendDiscoveredOnes() {
        runner.withPropertyValues("openapi-fn.specs.pets.base-path=/elsewhere", "openapi-fn.specs.copy.location=classpath:META-INF/openapi-fn/pets/openapi.yaml",
                "openapi-fn.clients.pets.base-url=https://pets.example.com", "openapi-fn.clients.pets.validate=true")
            .run(context -> {
                var runtimes = context.getBean(OpenApiRuntimes.class);
                assertThat(runtimes.all()).containsOnlyKeys("pets", "copy");
                assertThat(runtimes.get("pets").validator().options().basePaths()).containsExactly("/elsewhere");
                var client = context.getBean(OpenApiFnProperties.class).clients().get("pets");
                assertThat(client.baseUrl()).isEqualTo("https://pets.example.com");
                assertThat(client.validate()).isTrue();
            });
    }

    @Test
    void failsFastOnBrokenSpecs() {
        runner.withPropertyValues("openapi-fn.specs.broken.base-path=/x")
            .run(context -> assertThat(context).getFailure().hasRootCauseMessage("OpenAPI description 'broken' has no location"));
        runner.withPropertyValues("openapi-fn.specs.broken.location=classpath:missing.yaml")
            .run(context -> assertThat(context).getFailure().rootCause().hasMessageStartingWith("Invalid OpenAPI description 'broken'"));
    }

    @Test
    void mergesDescriptors() {
        var discovered = Map.of("a", new OpenApiFnProperties.Spec("loc-a", "/a"));
        var merged = SpecDescriptors.merge(discovered, Map.of("a", new OpenApiFnProperties.Spec(null, null), "b",
            new OpenApiFnProperties.Spec("loc-b", null)));
        assertThat(merged).containsEntry("a", new OpenApiFnProperties.Spec("loc-a", "/a")).containsEntry("b", new OpenApiFnProperties.Spec("loc-b", null));
        assertThat(SpecDescriptors.merge(discovered, Map.of("a", new OpenApiFnProperties.Spec("loc-x", "/x"))))
            .containsEntry("a", new OpenApiFnProperties.Spec("loc-x", "/x"));
        var broken = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("unreadable");
            }
        };
        assertThatThrownBy(() -> SpecDescriptors.discover(broken)).isInstanceOf(UncheckedIOException.class);
    }
}
