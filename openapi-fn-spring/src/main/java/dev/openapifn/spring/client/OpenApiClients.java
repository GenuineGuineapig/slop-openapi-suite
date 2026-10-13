package dev.openapifn.spring.client;

import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.autoconfigure.OpenApiFnProperties;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates the {@link OpenApiHttpClient}s of generated clients from configuration: the base URL
 * ({@code openapi-fn.clients.<name>.base-url}, default the first server URL) and optional
 * validation of requests and responses ({@code openapi-fn.clients.<name>.validate}).
 */
public final class OpenApiClients {

    private final OpenApiFnProperties properties;
    private final Supplier<RestClient.Builder> builders;
    private final Supplier<OpenApiRuntimes> runtimes;
    private final JsonMapper mapper;

    public OpenApiClients(OpenApiFnProperties properties, Supplier<RestClient.Builder> builders, Supplier<OpenApiRuntimes> runtimes, JsonMapper mapper) {
        this.properties = properties;
        this.builders = builders;
        this.runtimes = runtimes;
        this.mapper = mapper;
    }

    /** The HTTP client for API {@code name}. */
    public OpenApiHttpClient http(String name, String defaultBaseUrl) {
        var settings = Optional.ofNullable(properties.clients().get(name));
        var builder = builders.get();
        if (settings.filter(OpenApiFnProperties.Client::validate).isPresent()) {
            builder = builder.requestInterceptor(new OpenApiClientValidationInterceptor(runtimes.get().get(name).validator(), true));
        }
        var baseUrl = settings.map(OpenApiFnProperties.Client::baseUrl).orElse(null);
        return new OpenApiHttpClient(builder.build(), baseUrl != null ? baseUrl : defaultBaseUrl, mapper);
    }
}
