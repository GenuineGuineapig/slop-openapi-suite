package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonParser;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeExpressionsTest {

    private static final RuntimeExpressions.Context CONTEXT = new RuntimeExpressions.Context("https://api.example.com/pets/7?q=x", "POST",
        Optional.of(201), Map.of("path.petId", JsonNumber.of(7), "query.q", JsonString.of("x"), "header.x-trace", JsonString.of("a$1\\b")),
        Optional.of(json("{\"url\": \"https://hooks.example.com\", \"tags\": [\"a\", \"b\"]}")),
        Map.of("location", JsonString.of("/pets/7")), Optional.of(json("{\"id\": 7, \"a/b\": true}")));

    private static final RuntimeExpressions.Context REQUEST_ONLY = new RuntimeExpressions.Context("/pets", "GET", Optional.empty(), Map.of(),
        Optional.empty(), Map.of(), Optional.empty());

    private static JsonValue json(String text) {
        return JsonParser.parse(text).orElseThrow(error -> new AssertionError(error.toString()));
    }

    private static Optional<String> check(String text) {
        return RuntimeExpressions.check(JsonString.of(text));
    }

    private static Optional<JsonValue> value(String text) {
        return value(text, CONTEXT);
    }

    private static Optional<JsonValue> value(String text, RuntimeExpressions.Context context) {
        return RuntimeExpressions.value(JsonString.of(text), context).orElseThrow(error -> new AssertionError(error));
    }

    @Test
    void recognizesExpressions() {
        assertThat(List.of("$url", "$method", "$statusCode", "$request.path.id", "$request.query.a.b", "$request.header.X-Trace",
            "$response.header.Location", "$request.body", "$request.body#/a/0", "$response.body#")).allMatch(RuntimeExpressions::isExpression);
        assertThat(List.of("$", "$uri", "$request.cookie.a", "$response.path.id", "$response.query.q", "$request.header.", "$request.header.a b",
            "$request.path.", "$request.bodyx", "url")).noneMatch(RuntimeExpressions::isExpression);
    }

    @Test
    void checksValuesThatMayHoldExpressions() {
        assertThat(RuntimeExpressions.check(JsonNumber.of(3))).isEmpty();
        assertThat(check("plain")).isEmpty();
        assertThat(check("$url")).isEmpty();
        assertThat(check("{$url}/x/{$request.path.petId}")).isEmpty();
        assertThat(check("$nope")).contains("malformed runtime expression '$nope'");
        assertThat(check("a{$nope}")).contains("malformed runtime expression '$nope' in 'a{$nope}'");
    }

    @Test
    void evaluatesExpressions() {
        assertThat(value("$url")).contains(JsonString.of("https://api.example.com/pets/7?q=x"));
        assertThat(value("$method")).contains(JsonString.of("POST"));
        assertThat(value("$statusCode")).contains(JsonNumber.of(201));
        assertThat(value("$request.path.petId")).contains(JsonNumber.of(7));
        assertThat(value("$request.query.q")).contains(JsonString.of("x"));
        assertThat(value("$request.header.X-Trace")).contains(JsonString.of("a$1\\b"));
        assertThat(value("$request.body#/url")).contains(JsonString.of("https://hooks.example.com"));
        assertThat(value("$request.body#/tags")).contains(json("[\"a\", \"b\"]"));
        assertThat(value("$request.body")).isEqualTo(CONTEXT.requestBody());
        assertThat(value("$response.header.LOCATION")).contains(JsonString.of("/pets/7"));
        assertThat(value("$response.body#/id")).contains(JsonNumber.of(7));
        assertThat(value("$response.body#/a~1b")).contains(JsonBoolean.TRUE);
        assertThat(value("$response.body#")).isEqualTo(CONTEXT.responseBody());
    }

    @Test
    void selectsNothingWhenTheValueIsAbsent() {
        assertThat(value("$request.query.nope")).isEmpty();
        assertThat(value("$request.header.nope")).isEmpty();
        assertThat(value("$response.header.nope")).isEmpty();
        assertThat(value("$response.body#/nope")).isEmpty();
        assertThat(value("$response.body#nope")).isEmpty();
        assertThat(value("$statusCode", REQUEST_ONLY)).isEmpty();
        assertThat(value("$request.body", REQUEST_ONLY)).isEmpty();
        assertThat(value("$response.body#/id", REQUEST_ONLY)).isEmpty();
    }

    @Test
    void fillsInEmbeddedExpressions() {
        assertThat(value("{$request.body#/url}/pets/{$response.body#/id}?m={$method}")).contains(JsonString.of("https://hooks.example.com/pets/7?m=POST"));
        assertThat(value("tags={$request.body#/tags}")).contains(JsonString.of("tags=[\"a\",\"b\"]"));
        assertThat(value("x{$request.query.nope}y")).contains(JsonString.of("xy"));
        assertThat(value("trace {$request.header.x-trace}")).contains(JsonString.of("trace a$1\\b"));
        assertThat(value("no expressions")).contains(JsonString.of("no expressions"));
    }

    @Test
    void keepsConstantsAndRejectsMalformedExpressions() {
        assertThat(RuntimeExpressions.value(JsonNumber.of(3), CONTEXT).orElseThrow(error -> new AssertionError(error))).contains(JsonNumber.of(3));
        assertThat(RuntimeExpressions.value(json("{\"a\": \"$nope\"}"), CONTEXT).orElseThrow(error -> new AssertionError(error))).contains(json("{\"a\": \"$nope\"}"));
        assertThat(RuntimeExpressions.value(JsonString.of("$nope"), CONTEXT).<String>fold(ok -> "", error -> error)).isEqualTo("malformed runtime expression '$nope'");
    }
}
