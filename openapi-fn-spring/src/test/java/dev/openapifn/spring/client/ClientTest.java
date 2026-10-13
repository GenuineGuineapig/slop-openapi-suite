package dev.openapifn.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class ClientTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ParameterSerializer.Spec ID = new ParameterSerializer.Spec("id", ParameterLocation.PATH, ParameterStyle.SIMPLE, false, false);
    private static final ParameterSerializer.Spec LABEL = new ParameterSerializer.Spec("l", ParameterLocation.PATH, ParameterStyle.LABEL, false, false);
    private static final ParameterSerializer.Spec LIMIT = ParameterSerializer.Spec.of("limit", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec TAGS = new ParameterSerializer.Spec("tags", ParameterLocation.QUERY, ParameterStyle.FORM, false, false);
    private static final ParameterSerializer.Spec TRACE = ParameterSerializer.Spec.of("X-Trace", ParameterLocation.HEADER);
    private static final ParameterSerializer.Spec SESSION = ParameterSerializer.Spec.of("session", ParameterLocation.COOKIE);
    private static final ParameterSerializer.Spec QS = ParameterSerializer.Spec.of("qs", ParameterLocation.QUERYSTRING);

    private record Setup(OpenApiHttpClient client, MockRestServiceServer server) {
    }

    private static Setup setup(RestClient.Builder builder) {
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Setup(new OpenApiHttpClient(builder.build(), "https://api.example.com/v1/", MAPPER), server);
    }

    @Test
    void serializesParametersAndDecodesJson() {
        var setup = setup(RestClient.builder());
        assertThat(setup.client().baseUrl()).isEqualTo("https://api.example.com/v1");
        setup.server().expect(requestTo("https://api.example.com/v1/pets/a%20b/.x,y?limit=5&tags=a,b"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("X-Trace", "t1"))
            .andExpect(header("Cookie", "session=s%201"))
            .andExpect(header("Accept", "application/json, text/plain"))
            .andRespond(withSuccess("[{\"a\":1}]", MediaType.APPLICATION_JSON).header("X-Total", "3").header("X-Ids", "1, 2"));
        var call = ClientCall.of("GET", "/pets/{id}/{l}").param(ID, "a b").param(LABEL, List.of("x", "y")).param(LIMIT, 5).param(TAGS, List.of("a", "b"))
            .param(TRACE, "t1").param(SESSION, "s 1").param(LIMIT, null).accept("application/json", "text/plain");
        var result = setup.client().<List<Map<String, Integer>>>exchange(call, response -> {
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.header("X-Total", Integer.class)).isEqualTo(3);
            assertThat(response.header("X-Ids", new TypeReference<List<Integer>>() { })).containsExactly(1, 2);
            assertThat(response.header("X-Missing", String.class)).isNull();
            assertThat(response.header("X-Ids", Integer[].class)).containsExactly(1, 2);
            assertThat(response.headers().first("x-total")).contains("3");
            assertThat(response.contentType()).contains("application/json");
            return response.body(new TypeReference<List<Map<String, Integer>>>() { });
        });
        assertThat(result).containsExactly(Map.of("a", 1));
        setup.server().verify();
    }

    @Test
    void decodesNonJsonBodiesAndErrorStatuses() {
        var setup = setup(RestClient.builder());
        setup.server().expect(requestTo("https://api.example.com/v1/text")).andRespond(withStatus(HttpStatus.NOT_FOUND).body("missing").contentType(MediaType.TEXT_PLAIN));
        setup.server().expect(requestTo("https://api.example.com/v1/bytes")).andRespond(withSuccess(new byte[] {1, 2}, MediaType.IMAGE_PNG));
        setup.server().expect(requestTo("https://api.example.com/v1/number")).andRespond(withSuccess("42", MediaType.TEXT_PLAIN));
        setup.server().expect(requestTo("https://api.example.com/v1/empty")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        setup.server().expect(requestTo("https://api.example.com/v1/json-bytes")).andRespond(withSuccess("\"AQI=\"", MediaType.APPLICATION_JSON));
        setup.server().expect(requestTo("https://api.example.com/v1/untyped")).andRespond(withSuccess(new byte[] {3}, null));
        var client = setup.client();
        assertThat(client.<String>exchange(ClientCall.of("GET", "/text"), r -> r.status() + ":" + r.body(String.class))).isEqualTo("404:missing");
        assertThat(client.<byte[]>exchange(ClientCall.of("GET", "/bytes"), r -> r.body(byte[].class))).containsExactly(1, 2);
        assertThat(client.<Integer>exchange(ClientCall.of("GET", "/number"), r -> r.body(Integer.class))).isEqualTo(42);
        assertThat(client.<Boolean>exchange(ClientCall.of("GET", "/empty"), r -> r.body(String.class) == null && r.bytes().length == 0)).isTrue();
        assertThat(client.<byte[]>exchange(ClientCall.of("GET", "/json-bytes"), r -> r.body(byte[].class))).containsExactly(1, 2);
        assertThat(client.<FilePart>exchange(ClientCall.of("GET", "/untyped"), r -> r.body(FilePart.class)))
            .isEqualTo(new FilePart(null, FilePart.OCTET_STREAM, new byte[] {3}));
        setup.server().verify();
    }

    @Test
    void encodesBodies() {
        var setup = setup(RestClient.builder());
        setup.server().expect(requestTo("https://api.example.com/v1/json")).andExpect(header("Content-Type", "application/json"))
            .andExpect(content().string("{\"a\":1}")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/form")).andExpect(content().string("a=1&b=x%20y")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/file")).andExpect(header("Content-Type", "image/png"))
            .andExpect(content().bytes(new byte[] {7})).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/raw")).andExpect(content().bytes(new byte[] {8})).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/text")).andExpect(content().string("plain")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/multipart")).andExpect(request -> {
            var type = request.getHeaders().getContentType().toString();
            var body = new String(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8);
            assertThat(type).startsWith("multipart/form-data;boundary=openapi-fn-");
            assertThat(body).contains("name=\"file\"; filename=\"a.png\"", "Content-Type: image/png", "name=\"meta\"", "{\"a\":1}",
                "name=\"tags\"", "x", "y", "name=\"count\"", "\r\n3\r\n");
            assertThat(body).doesNotContain("absent");
        }).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/qs?a=1&b=2")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/qs?raw=text")).andRespond(withSuccess());
        var client = setup.client();
        client.exchange(ClientCall.of("POST", "/json").body("application/json", Map.of("a", 1)), r -> null);
        client.exchange(ClientCall.of("POST", "/form").body("application/x-www-form-urlencoded", new java.util.TreeMap<>(Map.of("a", 1, "b", "x y"))), r -> null);
        client.exchange(ClientCall.of("PUT", "/file").body("image/png", FilePart.of(new byte[] {7})), r -> null);
        client.exchange(ClientCall.of("PUT", "/raw").body("application/octet-stream", new byte[] {8}), r -> null);
        client.exchange(ClientCall.of("PUT", "/text").body("text/plain", "plain").body("text/plain", "plain"), r -> null);
        client.exchange(ClientCall.of("POST", "/multipart").multipart(Arrays.asList(
            ClientCall.Part.of("file", FilePart.of("a.png", "image/png", new byte[] {1})),
            ClientCall.Part.of("meta", Map.of("a", 1), "application/json"),
            ClientCall.Part.of("tags", List.of("x", "y")),
            ClientCall.Part.of("count", 3),
            ClientCall.Part.of("absent", null),
            ClientCall.Part.of("absent2", null, "application/json"))), r -> null);
        client.exchange(ClientCall.of("GET", "/qs").param(QS, new java.util.TreeMap<>(Map.of("a", 1, "b", 2))), r -> null);
        client.exchange(ClientCall.of("GET", "/qs").param(QS, "raw=text"), r -> null);
        assertThat(ClientCall.of("GET", "/x").body("text/plain", null).body()).isNull();
        setup.server().verify();
    }

    @Test
    void rejectsIncompleteCalls() {
        var client = setup(RestClient.builder()).client();
        assertThatThrownBy(() -> client.uri(ClientCall.of("GET", "/pets/{id}"))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Missing path parameter 'id'");
        assertThatThrownBy(() -> client.encode(new ClientCall.Body("nonsense", "x"))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid content type nonsense");
        assertThat(client.uri(ClientCall.of("GET", "/a").param(ParameterSerializer.Spec.of("h", ParameterLocation.HEADER), "x")))
            .isEqualTo(URI.create("https://api.example.com/v1/a"));
        assertThat(client.uri(ClientCall.of("GET", "/a/{id}").param(LIMIT, 1).param(ID, 2))).isEqualTo(URI.create("https://api.example.com/v1/a/2?limit=1"));
        var bare = new OpenApiHttpClient(RestClient.create(), "https://h", MAPPER);
        assertThat(bare.uri(ClientCall.of("GET", "/a"))).isEqualTo(URI.create("https://h/a"));
        assertThat(new String(client.encode(new ClientCall.Body("application/json", List.of(1, 2))).body(), StandardCharsets.UTF_8)).isEqualTo("[1,2]");
        var note = client.encode(new ClientCall.Body("multipart/form-data", List.of(new ClientCall.Part("note", "x", "text/plain"))));
        assertThat(new String(note.body(), StandardCharsets.UTF_8)).contains("Content-Type: text/plain", "\r\nx\r\n");
    }

    @Test
    void validatingInterceptor() {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            servers: [{url: https://api.example.com/v1}]
            paths:
              /pets:
                post:
                  requestBody:
                    required: true
                    content: {application/json: {schema: {type: object, required: [name]}}}
                  responses:
                    '200': {description: ok, content: {application/json: {schema: {type: object, required: [id]}}}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var validator = OpenApiValidator.of(api);
        var strict = RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true));
        var setup = setup(strict);
        setup.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        setup.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var valid = ClientCall.of("POST", "/pets").body("application/json", Map.of("name", "Rex"));
        assertThat(setup.client().<Map<String, Integer>>exchange(valid, r -> r.body(new TypeReference<Map<String, Integer>>() { }))).containsEntry("id", 1);
        assertThatThrownBy(() -> setup.client().exchange(valid, r -> null)).isInstanceOf(OpenApiValidationException.class)
            .hasMessageContaining("response.body.schema.required");
        assertThatThrownBy(() -> setup.client().exchange(ClientCall.of("POST", "/pets").body("application/json", Map.of()), r -> null))
            .isInstanceOf(OpenApiValidationException.class).hasMessageContaining("request.body.schema.required");
        var lenient = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, false)));
        lenient.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        lenient.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess());
        assertThat(lenient.client().<Integer>exchange(valid, ClientResponse::status)).isEqualTo(200);
        assertThat(lenient.client().<Integer>exchange(valid, ClientResponse::status)).isEqualTo(200);
        var unknown = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true)));
        unknown.server().expect(requestTo("https://api.example.com/v1/other")).andRespond(withSuccess());
        assertThatThrownBy(() -> unknown.client().exchange(ClientCall.of("GET", "/other"), r -> null)).isInstanceOf(OpenApiValidationException.class);
    }

    @Test
    void interceptorBuffersResponses() throws Exception {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"),
            "openapi: 3.1.0\ninfo: {title: t, version: v}\npaths:\n  /x:\n    get:\n      responses: {'200': {description: ok}}\n")
            .orElseThrow(d -> new AssertionError(d.toString()));
        var interceptor = new OpenApiClientValidationInterceptor(OpenApiValidator.of(api), true);
        var request = new org.springframework.mock.http.client.MockClientHttpRequest(HttpMethod.GET, URI.create("https://h/x"));
        var original = new org.springframework.mock.http.client.MockClientHttpResponse(new byte[] {1}, HttpStatus.OK);
        original.getHeaders().add("X-A", "1");
        var response = interceptor.intercept(request, new byte[0], (r, b) -> original);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getStatusText()).isEqualTo("OK");
        assertThat(response.getHeaders().getFirst("X-A")).isEqualTo("1");
        assertThat(response.getBody().readAllBytes()).containsExactly(1);
        assertThat(response.getBody().readAllBytes()).containsExactly(1);
        response.close();
    }
}
