package dev.openapifn.spring.client;

import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.http.RuntimeExpressions;
import dev.openapifn.json.JsonParser;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.spring.JacksonJson;
import java.net.URI;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the runtime expressions of Link Objects and callback URLs select from one exchange (a call
 * and, for links, the response it got), converted to the Java types of the request they lead to.
 * Generated clients use it to turn a response into the request of a linked operation, and a request
 * into the URL of its callbacks.
 */
public final class ExchangeValues {

    private final JsonMapper mapper;
    private final RuntimeExpressions.Context context;

    ExchangeValues(JsonMapper mapper, RuntimeExpressions.Context context) {
        this.mapper = mapper;
        this.context = context;
    }

    /** What expressions are evaluated against. */
    public RuntimeExpressions.Context context() {
        return context;
    }

    /**
     * The value of a Link Object parameter or request body, given as JSON text: what an expression
     * selects, a string with its embedded expressions filled in, or a constant; {@code null} when an
     * expression selects nothing.
     *
     * @throws IllegalArgumentException if the value is malformed
     */
    public <T> @Nullable T value(String json, Class<T> type) {
        return evaluate(json).map(value -> mapper.convertValue(JacksonJson.toJsonNode(value), type)).orElse(null);
    }

    /** {@link #value(String, Class)} for generic types. */
    public <T> @Nullable T value(String json, TypeReference<T> type) {
        return evaluate(json).map(value -> mapper.convertValue(JacksonJson.toJsonNode(value), type)).orElse(null);
    }

    /**
     * The URL a callback URL expression (such as {@code {$request.body#/callbackUrl}/events}) gives.
     *
     * @throws IllegalArgumentException if the expression is malformed or gives no URL
     */
    public URI uri(String expression) {
        var value = RuntimeExpressions.value(JsonString.of(expression), context).orElseThrow(IllegalArgumentException::new)
            .map(ParameterSerializer::text).filter(text -> !text.isEmpty())
            .orElseThrow(() -> new IllegalArgumentException("The callback URL '" + expression + "' selects no value"));
        return URI.create(value);
    }

    private Optional<JsonValue> evaluate(String json) {
        var value = JsonParser.parse(json).orElseThrow(error -> new IllegalArgumentException(error.toString()));
        return RuntimeExpressions.value(value, context).orElseThrow(IllegalArgumentException::new);
    }
}
