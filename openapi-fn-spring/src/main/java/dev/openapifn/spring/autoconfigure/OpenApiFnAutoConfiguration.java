package dev.openapifn.spring.autoconfigure;

import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.load.Uris;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.SchemaValidator;
import dev.openapifn.spring.OpenApiExceptionHandler;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.servlet.OpenApiValidationFilter;
import dev.openapifn.validation.LevelPolicy;
import dev.openapifn.validation.OpenApiValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
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
    public OpenApiRuntimes openApiRuntimes(OpenApiFnProperties properties, ObjectProvider<JsonMapper> mapper) {
        var classLoader = OpenApiFnAutoConfiguration.class.getClassLoader();
        var loader = new OpenApiLoader(ResourceLoaders.bySchemes(Map.of("file", ResourceLoaders.fileSystem(),
            Uris.CLASSPATH_SCHEME, ResourceLoaders.classpath(classLoader))));
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
            var api = loader.load(location).orElseThrow(diagnostics -> new IllegalStateException("Invalid OpenAPI description '" + name + "' ("
                + location + "):\n" + diagnostics.stream().map(Object::toString).collect(Collectors.joining("\n"))));
            var basePath = spec.getValue().basePath();
            var options = OpenApiValidator.Options.DEFAULT.withLevels(levels)
                .withSchema(SchemaValidator.Options.DEFAULT.withAssertFormats(validation.assertFormats()))
                .withBasePaths(basePath == null ? List.of() : List.of(basePath));
            runtimes.add(new OpenApiRuntime(name, api, OpenApiValidator.of(api, options), jsonMapper, validation.request().enabled()));
        }
        return new OpenApiRuntimes(runtimes);
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
}
