package dev.openapifn.http;

import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI runtime expressions, as Link Objects and callback URLs use them: {@code $url},
 * {@code $method}, {@code $statusCode}, {@code $request.path.<name>}, {@code $request.query.<name>},
 * {@code $request.header.<name>}, {@code $request.body#/<pointer>} and the {@code $response.header}
 * and {@code $response.body} forms; and strings embedding expressions in braces, such as
 * {@code https://{$request.header.Host}/hooks/{$request.body#/id}}.
 */
public final class RuntimeExpressions {

    private static final Pattern EXPRESSION = Pattern.compile(
        "\\$(?:url|method|statusCode|request\\.(?:path|query)\\.[^{}]+|(?:request|response)\\.header\\.[!#$%&'*+.^_`|~0-9A-Za-z-]+"
            + "|(?:request|response)\\.body(?:#[^{}]*)?)");
    private static final Pattern EMBEDDED = Pattern.compile("\\{(\\$[^{}]*)}");

    /**
     * What expressions are evaluated against.
     *
     * @param url the request URL
     * @param method the request method
     * @param statusCode the response status, if there is a response
     * @param request request parameter values by {@code path.<name>}, {@code query.<name>} and
     *     {@code header.<name>} (header names in lower case)
     * @param requestBody the request body
     * @param responseHeaders response header values by lower-case name
     * @param responseBody the response body
     */
    public record Context(String url, String method, Optional<Integer> statusCode, Map<String, JsonValue> request, Optional<JsonValue> requestBody,
                          Map<String, JsonValue> responseHeaders, Optional<JsonValue> responseBody) {

        public Context {
            request = Map.copyOf(request);
            responseHeaders = Map.copyOf(responseHeaders);
        }
    }

    private RuntimeExpressions() {
    }

    /** Whether {@code text} is a runtime expression. */
    public static boolean isExpression(String text) {
        return EXPRESSION.matcher(text).matches();
    }

    /**
     * Checks a value that may hold expressions: an expression, a string embedding expressions in
     * braces, or a constant. Returns why it is malformed, if it is.
     */
    public static Optional<String> check(JsonValue value) {
        if (!(value instanceof JsonString string)) {
            return Optional.empty();
        }
        var text = string.value();
        if (text.startsWith("$")) {
            return isExpression(text) ? Optional.empty() : Optional.of("malformed runtime expression '" + text + "'");
        }
        Matcher embedded = EMBEDDED.matcher(text);
        while (embedded.find()) {
            if (!isExpression(embedded.group(1))) {
                return Optional.of("malformed runtime expression '" + embedded.group(1) + "' in '" + text + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * The value of a Link parameter, request body or callback URL: an expression evaluates to the
     * value it selects (empty when that is absent), a string with embedded expressions to the string
     * with their values filled in, and anything else to itself.
     */
    public static Result<Optional<JsonValue>, String> value(JsonValue value, Context context) {
        var problem = check(value);
        if (problem.isPresent()) {
            return Result.err(problem.get());
        }
        if (!(value instanceof JsonString string)) {
            return Result.ok(Optional.of(value));
        }
        var text = string.value();
        if (text.startsWith("$")) {
            return Result.ok(evaluate(text, context));
        }
        Matcher embedded = EMBEDDED.matcher(text);
        var out = new StringBuilder();
        while (embedded.find()) {
            var part = evaluate(embedded.group(1), context).map(ParameterSerializer::text).orElse("");
            embedded.appendReplacement(out, Matcher.quoteReplacement(part));
        }
        embedded.appendTail(out);
        return Result.ok(Optional.of(JsonString.of(out.toString())));
    }

    /** Evaluates a well-formed expression. */
    private static Optional<JsonValue> evaluate(String expression, Context context) {
        return switch (expression) {
            case "$url" -> Optional.of(JsonString.of(context.url()));
            case "$method" -> Optional.of(JsonString.of(context.method()));
            case "$statusCode" -> context.statusCode().map(JsonNumber::of);
            default -> {
                var response = expression.startsWith("$response.");
                var source = expression.substring(response ? "$response.".length() : "$request.".length());
                if (source.startsWith("body")) {
                    var body = response ? context.responseBody() : context.requestBody();
                    var pointer = source.length() > "body".length() ? source.substring("body#".length()) : "";
                    yield body.flatMap(json -> JsonPointer.parse(pointer).toOptional().flatMap(p -> p.resolve(json)));
                }
                if (source.startsWith("header.")) {
                    var name = source.substring("header.".length()).toLowerCase(Locale.ROOT);
                    yield Optional.ofNullable(response ? context.responseHeaders().get(name) : context.request().get("header." + name));
                }
                yield Optional.ofNullable(context.request().get(source));
            }
        };
    }
}
