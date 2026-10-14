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
                assertThat(runtimes.all()).containsOnlyKeys("pets", "remote", "copy");
                assertThat(runtimes.get("pets").validator().options().basePaths()).containsExactly("/elsewhere");
                var client = context.getBean(OpenApiFnProperties.class).clients().get("pets");
                assertThat(client.baseUrl()).isEqualTo("https://pets.example.com");
                assertThat(client.validate()).isTrue();
            });
    }

    @Test
    void remoteReferencesAreLoadedFromTheirBundledCopies() {
        runner.run(context -> {
            var remote = context.getBean(OpenApiRuntimes.class).get("remote");
            var schema = remote.api().operations().getFirst().operation().responses().forStatus(200).orElseThrow().content().get("application/json")
                .schema().orElseThrow();
            assertThat(remote.api().schemas().deref(schema).document().toString()).isEqualTo("https://schemas.example.com/common/pet.yaml");
            assertThat(remote.api().schemas().deref(schema).required()).containsExactly("name");
        });
        runner.withPropertyValues("openapi-fn.specs.remote.aliases[https://schemas.example.com/common/pet.yaml]=%zz")
            .run(context -> assertThat(context).getFailure().rootCause().hasMessageStartingWith("Invalid alias in OpenAPI description 'remote': "));
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
        var hook = new OpenApiFnProperties.Receiver("hook", "/webhooks/hook");
        var discovered = Map.of("a", new OpenApiFnProperties.Spec("loc-a", "/a", Map.of("https://x/a.yaml", "classpath:a.yaml"), Map.of("hook", hook)));
        var merged = SpecDescriptors.merge(discovered, Map.of(
            "a", new OpenApiFnProperties.Spec(null, null, Map.of("https://x/b.yaml", "file:/b.yaml"), Map.of("hook", new OpenApiFnProperties.Receiver(null,
                "/in/hook"), "other", new OpenApiFnProperties.Receiver("other", "/in/other"))),
            "b", new OpenApiFnProperties.Spec("loc-b", null, Map.of(), Map.of())));
        assertThat(merged).containsEntry("a", new OpenApiFnProperties.Spec("loc-a", "/a", Map.of("https://x/a.yaml", "classpath:a.yaml",
                "https://x/b.yaml", "file:/b.yaml"), Map.of("hook", new OpenApiFnProperties.Receiver("hook", "/in/hook"), "other",
                new OpenApiFnProperties.Receiver("other", "/in/other"))))
            .containsEntry("b", new OpenApiFnProperties.Spec("loc-b", null, Map.of(), Map.of()));
        assertThat(SpecDescriptors.merge(discovered, Map.of("a", new OpenApiFnProperties.Spec("loc-x", "/x", Map.of(), Map.of("hook",
                new OpenApiFnProperties.Receiver("renamed", null))))))
            .containsEntry("a", new OpenApiFnProperties.Spec("loc-x", "/x", Map.of("https://x/a.yaml", "classpath:a.yaml"), Map.of("hook",
                new OpenApiFnProperties.Receiver("renamed", "/webhooks/hook"))));
        var broken = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("unreadable");
            }
        };
        assertThatThrownBy(() -> SpecDescriptors.discover(broken)).isInstanceOf(UncheckedIOException.class);
    }
}
