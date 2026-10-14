package dev.openapifn.spring.autoconfigure;

import dev.openapifn.spring.ResponseMode;
import dev.openapifn.validation.Level;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuration under {@code openapi-fn}.
 *
 * @param specs API descriptions by name (in addition to, or overriding, the ones generated code
 *     registers under {@code META-INF/openapi-fn/<name>/spec.properties})
 * @param validation validation settings
 * @param clients settings of generated clients by API name
 */
@ConfigurationProperties("openapi-fn")
public record OpenApiFnProperties(@DefaultValue Map<String, Spec> specs, @DefaultValue Validation validation, @DefaultValue Map<String, Client> clients) {

    /**
     * One API description.
     *
     * @param location {@code classpath:...}, a file path or a URL
     * @param basePath the path the API is mounted on, when it differs from the server URLs
     * @param aliases documents to load from another location, by URI: generated bundles map remote
     *     references to their bundled copies, so that nothing is fetched at runtime
     * @param receivers the generated controllers receiving webhooks and callbacks, by Java method name
     */
    public record Spec(@Nullable String location, @Nullable String basePath, @DefaultValue Map<String, String> aliases,
                       @DefaultValue Map<String, Receiver> receivers) {
    }

    /**
     * Where a webhook or callback is received.
     *
     * @param operation the operation id (registered by generated code)
     * @param path the path the receiving controller is mapped to
     */
    public record Receiver(@Nullable String operation, @Nullable String path) {
    }

    /**
     * Validation settings.
     *
     * @param request request validation
     * @param response response validation
     * @param rejectUnknownPaths whether requests to paths no description covers are rejected with 404
     * @param excludePaths path patterns that are never validated
     * @param levels issue levels by key prefix, e.g. {@code request.parameter.query.unknown: WARN}
     * @param assertFormats whether {@code format} is asserted
     * @param maxBodySize the largest body that is cached and validated
     * @param filterOrder order of the validation filter
     */
    public record Validation(@DefaultValue Request request, @DefaultValue Response response, @DefaultValue("false") boolean rejectUnknownPaths,
                             @DefaultValue({"/actuator/**", "/error"}) List<String> excludePaths, @DefaultValue Map<String, Level> levels,
                             @DefaultValue("true") boolean assertFormats, @DefaultValue("10MB") DataSize maxBodySize,
                             @DefaultValue("0") int filterOrder) {
    }

    /**
     * Request validation.
     *
     * @param enabled whether invalid requests are rejected (by the filter and when binding)
     */
    public record Request(@DefaultValue("true") boolean enabled) {
    }

    /**
     * Response validation.
     *
     * @param enabled whether responses are validated
     * @param mode whether invalid responses are logged or replaced by a 500 problem
     */
    public record Response(@DefaultValue("true") boolean enabled, @DefaultValue("LOG") ResponseMode mode) {
    }

    /**
     * A generated client.
     *
     * @param baseUrl the base URL requests are sent to (defaults to the first server URL)
     * @param validate whether requests and responses of the client are validated
     */
    public record Client(@Nullable String baseUrl, @DefaultValue("false") boolean validate) {
    }
}
