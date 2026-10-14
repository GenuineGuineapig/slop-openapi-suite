package dev.openapifn.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonParser;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.spring.FilePart;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ExchangeValuesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final OpenApiHttpClient CLIENT = new OpenApiHttpClient(RestClient.create(), "https://api.example.com/v1", MAPPER);
    private static final ParameterSerializer.Spec ID = ParameterSerializer.Spec.of("id", ParameterLocation.PATH);
    private static final ParameterSerializer.Spec TAGS = ParameterSerializer.Spec.of("tags", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec TRACE = ParameterSerializer.Spec.of("X-Trace", ParameterLocation.HEADER);
    private static final ParameterSerializer.Spec SESSION = ParameterSerializer.Spec.of("session", ParameterLocation.COOKIE);
    private static final UUID TRACE_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    record Pet(long id, String name) {
    }

    private static JsonValue json(String text) {
        return JsonParser.parse(text).orElseThrow(error -> new AssertionError(error.toString()));
    }

    private static ClientCall call() {
        return ClientCall.of("POST", "/pets/{id}").param(ID, 7).param(TAGS, List.of("a", "b")).param(TRACE, TRACE_ID).param(SESSION, "s")
            .accept("application/json").body("application/json", new Pet(0, "Rex"));
    }

    @Test
    void exposesTheCallAndTheResponseToExpressions() {
        var values = CLIENT.values(call(), 201, new Pet(7, "Rex"), "Location", "/pets/7", "X-Missing", null);
        var context = values.context();
        assertThat(context.url()).isEqualTo("https://api.example.com/v1/pets/7?tags=a&tags=b");
        assertThat(context.method()).isEqualTo("POST");
        assertThat(context.statusCode()).contains(201);
        assertThat(context.request()).containsEntry("path.id", JsonNumber.of(7)).containsEntry("query.tags", json("[\"a\", \"b\"]"))
            .containsEntry("header.x-trace", JsonString.of(TRACE_ID.toString())).containsEntry("header.accept", JsonString.of("application/json"))
            .containsEntry("header.cookie", JsonString.of("session=s")).containsEntry("cookie.session", JsonString.of("s"));
        assertThat(context.requestBody()).contains(json("{\"id\": 0, \"name\": \"Rex\"}"));
        assertThat(context.responseHeaders()).containsOnly(Map.entry("location", JsonString.of("/pets/7")));
        assertThat(context.responseBody()).contains(json("{\"id\": 7, \"name\": \"Rex\"}"));
    }

    @Test
    void convertsWhatExpressionsSelectToJavaTypes() {
        var values = CLIENT.values(call(), 201, new Pet(7, "Rex"), "Location", "/pets/7");
        assertThat(values.value("\"$response.body#/id\"", Long.class)).isEqualTo(7L);
        assertThat(values.value("\"$request.header.X-Trace\"", UUID.class)).isEqualTo(TRACE_ID);
        assertThat(values.value("\"$request.query.tags\"", new TypeReference<List<String>>() { })).containsExactly("a", "b");
        assertThat(values.value("\"{$response.header.Location}/photo\"", String.class)).isEqualTo("/pets/7/photo");
        assertThat(values.value("{\"id\": 1, \"name\": \"constant\"}", Pet.class)).isEqualTo(new Pet(1, "constant"));
        assertThat(values.value("\"$response.body#/missing\"", String.class)).isNull();
        assertThat(values.value("\"$request.query.missing\"", new TypeReference<List<String>>() { })).isNull();
        assertThatThrownBy(() -> values.value("\"$nope\"", String.class)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("malformed runtime expression '$nope'");
        assertThatThrownBy(() -> values.value("{", String.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void givesCallbackUrls() {
        var values = CLIENT.values(call().param(ParameterSerializer.Spec.of("X-Callback", ParameterLocation.HEADER), "https://hooks.example.com/a"));
        assertThat(values.context().statusCode()).isEmpty();
        assertThat(values.context().responseBody()).isEmpty();
        assertThat(values.uri("{$request.header.X-Callback}")).isEqualTo(URI.create("https://hooks.example.com/a"));
        assertThat(values.uri("{$request.header.X-Callback}/{$request.body#/name}?m={$method}")).isEqualTo(URI.create("https://hooks.example.com/a/Rex?m=POST"));
        assertThat(values.uri("$request.header.X-Callback")).isEqualTo(URI.create("https://hooks.example.com/a"));
        assertThatThrownBy(() -> values.uri("{$request.header.X-Other}")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("The callback URL '{$request.header.X-Other}' selects no value");
        assertThatThrownBy(() -> values.uri("$request.header.X-Other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> values.uri("{$nope}")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("malformed runtime expression '$nope' in '{$nope}'");
    }

    @Test
    void bodiesWithoutJsonSelectNothing() {
        var file = FilePart.of("a.png", "image/png", new byte[] {1});
        var form = ClientCall.of("POST", "/upload").multipart(Arrays.asList(ClientCall.Part.of("file", file), ClientCall.Part.of("caption", "hi")));
        assertThat(CLIENT.values(form, 200, new byte[] {1}).context().requestBody()).contains(json("{\"caption\": \"hi\"}"));
        assertThat(CLIENT.values(form, 200, new byte[] {1}).context().responseBody()).isEmpty();
        var streamed = ClientCall.of("POST", "/events").body("application/jsonl", Stream.of(1, 2));
        assertThat(CLIENT.values(streamed, 200, Stream.of(3)).context().requestBody()).isEmpty();
        assertThat(CLIENT.values(streamed, 200, Stream.of(3)).context().responseBody()).isEmpty();
        assertThat(CLIENT.values(ClientCall.of("PUT", "/photo").body("image/png", file)).context().requestBody()).isEmpty();
        assertThat(CLIENT.values(ClientCall.of("GET", "/text"), 200, "plain").context().responseBody()).contains(JsonString.of("plain"));
        assertThat(CLIENT.values(ClientCall.of("GET", "/none")).context().requestBody()).isEqualTo(Optional.empty());
    }
}
