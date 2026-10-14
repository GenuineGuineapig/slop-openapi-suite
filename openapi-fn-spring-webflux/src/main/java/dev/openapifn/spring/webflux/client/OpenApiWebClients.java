package dev.openapifn.spring.webflux.client;

import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.autoconfigure.OpenApiFnProperties;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates the {@link OpenApiWebClient}s of generated reactive clients from configuration: the base
 * URL ({@code openapi-fn.clients.<name>.base-url}, default the first server URL) and optional
 * validation of requests and responses ({@code openapi-fn.clients.<name>.validate}).
 */
public final class OpenApiWebClients {

    private final OpenApiFnProperties properties;
    private final Supplier<WebClient.Builder> builders;
    private final Supplier<OpenApiRuntimes> runtimes;
    private final JsonMapper mapper;

    public OpenApiWebClients(OpenApiFnProperties properties, Supplier<WebClient.Builder> builders, Supplier<OpenApiRuntimes> runtimes, JsonMapper mapper) {
        this.properties = properties;
        this.builders = builders;
        this.runtimes = runtimes;
        this.mapper = mapper;
    }

    /** The reactive HTTP client for API {@code name}. */
    public OpenApiWebClient http(String name, String defaultBaseUrl) {
        var settings = Optional.ofNullable(properties.clients().get(name));
        var validator = settings.filter(OpenApiFnProperties.Client::validate).map(client -> runtimes.get().get(name).validator()).orElse(null);
        var baseUrl = settings.map(OpenApiFnProperties.Client::baseUrl).orElse(null);
        return new OpenApiWebClient(builders.get().build(), baseUrl != null ? baseUrl : defaultBaseUrl, mapper, validator, true);
    }
}
