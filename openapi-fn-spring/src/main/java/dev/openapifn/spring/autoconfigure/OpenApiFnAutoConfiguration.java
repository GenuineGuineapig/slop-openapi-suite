package dev.openapifn.spring.autoconfigure;

import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.load.Uris;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.SchemaValidator;
import dev.openapifn.spring.OpenApiExceptionHandler;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.StreamedBodyConverter;
import dev.openapifn.spring.client.OpenApiClients;
import dev.openapifn.spring.servlet.OpenApiValidationFilter;
import dev.openapifn.validation.LevelPolicy;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the application's API descriptions and provides their runtimes, the validation filter
 * and problem-detail error handling.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties(OpenApiFnProperties.class)
public class OpenApiFnAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenApiRuntimes openApiRuntimes(OpenApiFnProperties properties, ObjectProvider<JsonMapper> mapper, ResourceLoader resourceLoader) {
        var classLoader = Objects.requireNonNullElse(resourceLoader.getClassLoader(), OpenApiFnAutoConfiguration.class.getClassLoader());
        var documents = ResourceLoaders.bySchemes(Map.of("file", ResourceLoaders.fileSystem(), Uris.CLASSPATH_SCHEME, ResourceLoaders.classpath(classLoader)));
        var jsonMapper = mapper.getIfAvailable(JsonMapper::new);
        var validation = properties.validation();
        var levels = LevelPolicy.DEFAULT;
        for (var level : validation.levels().entrySet()) {
            levels = levels.with(level.getKey(), level.getValue());
        }
        var runtimes = new ArrayList<OpenApiRuntime>();
        var specs = SpecDescriptors.merge(SpecDescriptors.discover(classLoader), properties.specs());
        for (var spec : specs.entrySet()) {
            var name = spec.getKey();
            var location = spec.getValue().location();
            if (location == null) {
                throw new IllegalStateException("OpenAPI description '" + name + "' has no location");
            }
            var aliases = new HashMap<URI, URI>();
            spec.getValue().aliases().forEach((from, to) -> aliases.put(uri(name, from), uri(name, to)));
            var api = new OpenApiLoader(ResourceLoaders.aliased(documents, aliases)).load(location).orElseThrow(diagnostics -> new IllegalStateException("Invalid OpenAPI description '" + name + "' ("
                + location + "):\n" + diagnostics.stream().map(Object::toString).collect(Collectors.joining("\n"))));
            var basePath = spec.getValue().basePath();
            var options = OpenApiValidator.Options.DEFAULT.withLevels(levels)
                .withSchema(SchemaValidator.Options.DEFAULT.withAssertFormats(validation.assertFormats()))
                .withBasePaths(basePath == null ? List.of() : List.of(basePath));
            var receivers = new java.util.LinkedHashMap<String, String>();
            spec.getValue().receivers().values().forEach(receiver -> receivers.put(receiver.path(), receiver.operation()));
            runtimes.add(new OpenApiRuntime(name, api, OpenApiValidator.of(api, options), jsonMapper, validation.request().enabled(), receivers));
        }
        return new OpenApiRuntimes(runtimes);
    }

    private static URI uri(String spec, String text) {
        return Uris.parse(text).orElseThrow(error -> new IllegalStateException("Invalid alias in OpenAPI description '" + spec + "': " + error));
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(name = "openApiValidationFilter")
    public FilterRegistrationBean<OpenApiValidationFilter> openApiValidationFilter(OpenApiRuntimes runtimes, OpenApiFnProperties properties) {
        var validation = properties.validation();
        var settings = new OpenApiValidationFilter.Settings(validation.request().enabled(), validation.response().enabled(),
            validation.response().mode(), validation.rejectUnknownPaths(), validation.excludePaths(), validation.maxBodySize().toBytes());
        var registration = new FilterRegistrationBean<>(new OpenApiValidationFilter(runtimes, settings));
        registration.setOrder(validation.filterOrder());
        registration.setName("openApiValidationFilter");
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean
    public OpenApiExceptionHandler openApiExceptionHandler() {
        return new OpenApiExceptionHandler();
    }

    /** Writes the streamed bodies of generated controllers item by item. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebMvcConfigurer openApiFnMessageConverters() {
        return new WebMvcConfigurer() {
            @Override
            public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
                builder.addCustomConverter(new StreamedBodyConverter());
            }
        };
    }

    /** Clients use the application's {@code RestClient.Builder} (a fresh clone each time) when there is one. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(RestClient.class)
    public OpenApiClients openApiClients(OpenApiFnProperties properties, ObjectProvider<RestClient.Builder> builder,
                                         ObjectProvider<OpenApiRuntimes> runtimes, ObjectProvider<JsonMapper> mapper) {
        return new OpenApiClients(properties, () -> builder.getIfAvailable(RestClient::builder).clone(), runtimes::getObject,
            mapper.getIfAvailable(JsonMapper::new));
    }
}
