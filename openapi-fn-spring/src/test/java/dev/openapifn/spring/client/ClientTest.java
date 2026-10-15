package dev.openapifn.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.openapifn.http.Headers;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.FilePart;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.LevelPolicy;
import dev.openapifn.validation.OpenApiValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
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
        setup.server().expect(requestTo("https://api.example.com/v1/lines")).andRespond(withSuccess("{\"a\":1}\n{\"a\":2}\n", MediaType.parseMediaType("application/jsonl")));
        setup.server().expect(requestTo("https://api.example.com/v1/xml")).andRespond(withSuccess("<r><a>1</a></r>", MediaType.APPLICATION_XML));
        setup.server().expect(requestTo("https://api.example.com/v1/broken")).andRespond(withSuccess("{\n", MediaType.parseMediaType("application/x-ndjson")));
        setup.server().expect(requestTo("https://api.example.com/v1/raw")).andRespond(withSuccess("x", null));
        var client = setup.client();
        assertThat(client.<String>exchange(ClientCall.of("GET", "/text"), r -> r.status() + ":" + r.body(String.class))).isEqualTo("404:missing");
        assertThat(client.<byte[]>exchange(ClientCall.of("GET", "/bytes"), r -> r.body(byte[].class))).containsExactly(1, 2);
        assertThat(client.<Integer>exchange(ClientCall.of("GET", "/number"), r -> r.body(Integer.class))).isEqualTo(42);
        assertThat(client.<Boolean>exchange(ClientCall.of("GET", "/empty"), r -> r.body(String.class) == null && r.bytes().length == 0)).isTrue();
        assertThat(client.<byte[]>exchange(ClientCall.of("GET", "/json-bytes"), r -> r.body(byte[].class))).containsExactly(1, 2);
        assertThat(client.<FilePart>exchange(ClientCall.of("GET", "/untyped"), r -> r.body(FilePart.class)))
            .isEqualTo(new FilePart(null, FilePart.OCTET_STREAM, new byte[] {3}));
        assertThat(client.<List<Map<String, Integer>>>exchange(ClientCall.of("GET", "/lines"), r -> r.body(new TypeReference<List<Map<String, Integer>>>() { })))
            .containsExactly(Map.of("a", 1), Map.of("a", 2));
        assertThat(client.<Map<String, String>>exchange(ClientCall.of("GET", "/xml"), r -> r.body(new TypeReference<Map<String, String>>() { })))
            .containsEntry("a", "1");
        assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/broken"), r -> r.body(Object.class)))
            .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Cannot decode application/x-ndjson content");
        assertThat(client.<Object>exchange(ClientCall.of("GET", "/raw"), r -> r.body(Object.class))).isEqualTo("x");
        setup.server().verify();
    }

    @Test
    void encodesBodies() {
        var setup = setup(RestClient.builder());
        setup.server().expect(requestTo("https://api.example.com/v1/json")).andExpect(header("Content-Type", "application/json"))
            .andExpect(content().string("{\"a\":1}")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/form")).andExpect(content().string("a=1&b=x+y")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/file")).andExpect(header("Content-Type", "image/png"))
            .andExpect(content().bytes(new byte[] {7})).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/raw")).andExpect(content().bytes(new byte[] {8})).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/text")).andExpect(content().string("plain")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/multipart")).andExpect(request -> {
            var type = request.getHeaders().getContentType().toString();
            var body = new String(((MockClientHttpRequest) request).getBodyAsBytes(), StandardCharsets.UTF_8);
            assertThat(type).startsWith("multipart/form-data;boundary=openapi-fn-");
            assertThat(body).contains("name=\"file\"; filename=\"a.png\"", "Content-Type: image/png", "name=\"meta\"", "{\"a\":1}",
                "name=\"tags\"", "x", "y", "name=\"count\"", "\r\n3\r\n", "name=\"unique\"", "\r\nu\r\n", "[1,2]");
            assertThat(body).doesNotContain("absent");
        }).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/qs?a=1&b=2")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/qs?raw=text")).andRespond(withSuccess());
        var client = setup.client();
        client.exchange(ClientCall.of("POST", "/json").body("application/json", Map.of("a", 1)), r -> null);
        client.exchange(ClientCall.of("POST", "/form").body("application/x-www-form-urlencoded", new TreeMap<>(Map.of("a", 1, "b", "x y"))), r -> null);
        client.exchange(ClientCall.of("PUT", "/file").body("image/png", FilePart.of(new byte[] {7})), r -> null);
        client.exchange(ClientCall.of("PUT", "/raw").body("application/octet-stream", new byte[] {8}), r -> null);
        client.exchange(ClientCall.of("PUT", "/text").body("text/plain", "plain").body("text/plain", "plain"), r -> null);
        client.exchange(ClientCall.of("POST", "/multipart").multipart(Arrays.asList(
            ClientCall.Part.of("file", FilePart.of("a.png", "image/png", new byte[] {1})),
            ClientCall.Part.of("meta", Map.of("a", 1), "application/json"),
            ClientCall.Part.of("tags", List.of("x", "y")),
            ClientCall.Part.of("count", 3),
            ClientCall.Part.of("unique", Set.of("u")),
            ClientCall.Part.of("matrix", List.of(List.of(1, 2)), "application/json"),
            ClientCall.Part.of("absent", null),
            ClientCall.Part.of("absent2", null, "application/json"))), r -> null);
        client.exchange(ClientCall.of("GET", "/qs").param(QS, new TreeMap<>(Map.of("a", 1, "b", 2))), r -> null);
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
        var mixed = client.encode(new ClientCall.Body("multipart/mixed", List.of(Map.of("a", 1), 2, FilePart.of("f.png", "image/png", new byte[] {65}))));
        assertThat(mixed.contentType()).startsWith("multipart/mixed; boundary=openapi-fn-");
        assertThat(new String(mixed.body(), StandardCharsets.UTF_8)).contains("Content-Type: application/json\r\n\r\n{\"a\":1}\r\n",
            "Content-Type: text/plain\r\n\r\n2\r\n", "Content-Type: image/png\r\n\r\nA\r\n");
        var form = client.encode(new ClientCall.Body("multipart/form-data", new TreeMap<>(Map.of("a", 1, "b", List.of("x", "y")))));
        assertThat(form.contentType()).startsWith("multipart/form-data; boundary=openapi-fn-");
        assertThat(new String(form.body(), StandardCharsets.UTF_8)).contains("name=\"a\"", "\r\n1\r\n", "name=\"b\"", "\r\nx\r\n", "\r\ny\r\n");
        var file = FilePart.of("f.bin", "application/x-thing", new byte[] {1});
        assertThat(client.encode(new ClientCall.Body("*/*", file)).contentType()).isEqualTo("application/x-thing");
        assertThat(client.encode(new ClientCall.Body("multipart/related", file)).contentType()).isEqualTo("application/x-thing");
        assertThat(client.encode(new ClientCall.Body("application/pdf", file)).contentType()).isEqualTo("application/pdf");
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
                  operationId: createPet
                  requestBody:
                    required: true
                    content: {application/json: {schema: {type: object, required: [name]}}}
                  responses:
                    '200': {description: ok, content: {application/json: {schema: {type: object, required: [id]}}}}
                  callbacks:
                    created:
                      '{$request.body#/callback}':
                        post:
                          operationId: petCreated
                          requestBody:
                            required: true
                            content: {application/json: {schema: {type: object, required: [id]}}}
                          responses:
                            '204': {description: received}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var validator = OpenApiValidator.of(api);
        var strict = RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true));
        var setup = setup(strict);
        setup.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        setup.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var valid = ClientCall.of("POST", "/pets").body("application/json", Map.of("name", "Rex"));
        assertThat(setup.client().<Map<String, Integer>>exchange(valid, r -> r.body(new TypeReference<Map<String, Integer>>() { }))).containsEntry("id", 1);
        assertThatThrownBy(() -> setup.client().exchange(valid, r -> null)).isInstanceOf(ClientValidationException.class)
            .hasMessageContaining("response.body.schema.required");
        assertThatThrownBy(() -> setup.client().exchange(ClientCall.of("POST", "/pets").body("application/json", Map.of()), r -> null))
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.schema.required")
            .hasMessageStartingWith("Request POST https://api.example.com/v1/pets violates the API description")
            .satisfies(e -> assertThat(((ClientValidationException) e).report().hasErrors()).isTrue());
        var lenient = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, false)));
        lenient.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        lenient.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess());
        assertThat(lenient.client().<Integer>exchange(valid, ClientResponse::status)).isEqualTo(200);
        assertThat(lenient.client().<Integer>exchange(valid, ClientResponse::status)).isEqualTo(200);
        var callbacks = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true)));
        callbacks.server().expect(requestTo("https://subscriber.example.com/hooks?x=1")).andRespond(withSuccess().body(new byte[0]));
        var subscriber = callbacks.client().to("https://subscriber.example.com/hooks");
        assertThat(subscriber.baseUrl()).isEqualTo("https://subscriber.example.com/hooks");
        var created = ClientCall.of("POST", "").operation("petCreated").param(ParameterSerializer.Spec.of("x", ParameterLocation.QUERY), 1);
        assertThatThrownBy(() -> subscriber.exchange(created.body("application/json", Map.of()), r -> null))
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.body.schema.required");
        assertThatThrownBy(() -> subscriber.exchange(created.body("application/json", Map.of("id", 1)), r -> null))
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("response.status.unknown");
        var named = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true)));
        named.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        named.server().expect(requestTo("https://api.example.com/v1/pets")).andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        assertThat(named.client().<Integer>exchange(valid.operation("unknownOperation"), ClientResponse::status)).isEqualTo(200);
        assertThat(named.client().<Integer>exchange(valid.operation("createPet"), ClientResponse::status)).isEqualTo(200);
        var unknown = setup(RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(validator, true)));
        unknown.server().expect(requestTo("https://api.example.com/v1/other")).andRespond(withSuccess());
        assertThatThrownBy(() -> unknown.client().exchange(ClientCall.of("GET", "/other"), r -> null)).isInstanceOf(ClientValidationException.class);
    }

    @Test
    void streamsItems() throws Exception {
        var lines = new ClientResponse(200, Headers.of("Content-Type", "application/jsonl"), "{\"a\":1}\n".getBytes(StandardCharsets.UTF_8),
            MAPPER);
        try (var items = lines.items(new TypeReference<Map<String, Integer>>() { })) {
            assertThat(items).containsExactly(Map.of("a", 1));
        }
        var array = new ClientResponse(200, Headers.of("Content-Type", "application/json"), "[1,2]".getBytes(StandardCharsets.UTF_8), MAPPER);
        assertThat(array.items(Integer.class)).containsExactly(1, 2);
        assertThat(new ClientResponse(204, Headers.EMPTY, new byte[0], MAPPER).items(Integer.class)).isEmpty();
        var closed = new AtomicInteger();
        var broken = new ClientResponse(200, Headers.of("Content-Type", "application/jsonl"),
            new ByteArrayInputStream("{\n".getBytes(StandardCharsets.UTF_8)), closed::incrementAndGet, MAPPER);
        assertThatThrownBy(() -> broken.items(Object.class).toList()).isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Cannot decode application/jsonl content: item 0 must be valid JSON");
        var unreadable = new ClientResponse(200, Headers.of("Content-Type", "application/jsonl"), new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }
        }, closed::incrementAndGet, MAPPER);
        assertThatThrownBy(unreadable::bytes).isInstanceOf(UncheckedIOException.class);
        assertThat(closed).hasValue(1);

        var setup = setup(RestClient.builder());
        var mixed = MediaType.parseMediaType("multipart/mixed");
        setup.server().expect(requestTo("https://api.example.com/v1/lines")).andExpect(header("Content-Type", "application/jsonl"))
            .andExpect(content().string("{\"a\":1}\n{\"a\":2}\n"))
            .andRespond(withSuccess("{\"b\":1}\n{\"b\":2}\n", MediaType.parseMediaType("application/jsonl")));
        setup.server().expect(requestTo("https://api.example.com/v1/parts"))
            .andExpect(request -> assertThat(request.getHeaders().getContentType()).satisfies(type -> assertThat(type.isCompatibleWith(mixed)).isTrue()))
            .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString())
                .contains("Content-Type: application/json\r\n\r\n{\"a\":1}\r\n", "Content-Type: image/png\r\n\r\nA\r\n"))
            .andRespond(withSuccess("x", MediaType.parseMediaType("application/jsonl")));
        setup.server().expect(requestTo("https://api.example.com/v1/fixed")).andExpect(header("Content-Type", "multipart/mixed; boundary=fixed"))
            .andExpect(content().string("--fixed\r\nContent-Type: text/plain\r\n\r\n1\r\n--fixed--\r\n")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/array")).andExpect(content().string("[1,2]")).andRespond(withSuccess());
        setup.server().expect(requestTo("https://api.example.com/v1/failing"))
            .andRespond(withSuccess("{\"b\":1}\n", MediaType.parseMediaType("application/jsonl")));
        var client = setup.client();
        assertThat(client.<List<Map<String, Integer>>>exchange(ClientCall.of("POST", "/lines").body("application/jsonl", Stream.of(Map.of("a", 1), Map.of("a", 2))),
            response -> {
                try (var items = response.items(new TypeReference<Map<String, Integer>>() { })) {
                    return items.toList();
                }
            })).containsExactly(Map.of("b", 1), Map.of("b", 2));
        assertThat(client.<Integer>exchange(ClientCall.of("POST", "/parts").body("multipart/mixed",
            Stream.of(Map.of("a", 1), FilePart.of("f.png", "image/png", new byte[] {65}))), ClientResponse::status)).isEqualTo(200);
        assertThat(client.<Integer>exchange(ClientCall.of("POST", "/fixed").body("multipart/mixed; boundary=fixed", Stream.of(1)), ClientResponse::status))
            .isEqualTo(200);
        assertThat(client.<Integer>exchange(ClientCall.of("POST", "/array").body("application/json", Stream.of(1, 2)), ClientResponse::status)).isEqualTo(200);
        assertThatThrownBy(() -> client.exchange(ClientCall.of("POST", "/invalid").body("nonsense", Stream.of(1)), ClientResponse::status))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid content type nonsense");
        assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/failing"), response -> {
            throw new IllegalStateException("decoder failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("decoder failed");
        setup.server().verify();
    }

    @Test
    void interceptorValidatesItemsWhileTheyAreRead() throws Exception {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /x:
                get:
                  responses:
                    '200':
                      description: ok
                      headers: {X-Required: {required: true, schema: {type: string}}}
                      content: {application/jsonl: {itemSchema: {type: object, required: [n]}}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var validator = OpenApiValidator.of(api);
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://h/x"));
        Function<String, MockClientHttpResponse> response = body -> {
            var mock = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            mock.getHeaders().add("Content-Type", "application/jsonl");
            mock.getHeaders().add("X-Required", "yes");
            return mock;
        };
        var valid = new OpenApiClientValidationInterceptor(validator, true).intercept(request, new byte[0], (r, b) -> response.apply("{\"n\":1}\n"));
        var body = valid.getBody();
        assertThat(body.read(new byte[0], 0, 0)).isZero();
        var read = new ByteArrayOutputStream();
        for (int b = body.read(); b >= 0; b = body.read()) {
            read.write(b);
        }
        assertThat(read.toString(StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}\n");
        assertThat(body.read(new byte[4], 0, 4)).isEqualTo(-1);

        var invalid = new OpenApiClientValidationInterceptor(validator, true).intercept(request, new byte[0], (r, b) -> response.apply("{\"n\":1}\n{}\n"));
        assertThatThrownBy(() -> invalid.getBody().readAllBytes()).isInstanceOf(ClientValidationException.class)
            .hasMessageContaining("response.body.schema.required");
        var logged = new OpenApiClientValidationInterceptor(validator, false).intercept(request, new byte[0], (r, b) -> response.apply("{}\n"));
        assertThat(logged.getBody().readAllBytes()).hasSize(3);
        assertThatThrownBy(() -> new OpenApiClientValidationInterceptor(validator, true).intercept(request, new byte[0], (r, b) -> {
            var headless = response.apply("{\"n\":1}\n");
            headless.getHeaders().remove("X-Required");
            return headless;
        })).isInstanceOf(ClientValidationException.class).hasMessageContaining("response.header.missing");
    }

    @Test
    void encodesFormPropertiesAndDecodesDelimitedHeaders() {
        var pipes = new ParameterSerializer.Spec("tags", ParameterLocation.QUERY, ParameterStyle.PIPE_DELIMITED, false, false);
        assertThat(ClientCall.of("GET", "/a").encoding("tags", pipes).body()).isNull();
        var call = ClientCall.of("POST", "/forms").body("application/x-www-form-urlencoded", Map.of("tags", List.of("a", "b"))).encoding("tags", pipes);
        assertThat(call.body().encodings()).containsOnlyKeys("tags");
        var client = setup(RestClient.builder()).client();
        assertThat(new String(client.encode(call.body()).body(), StandardCharsets.UTF_8)).isEqualTo("tags=a%7Cb");
        var typed = new ClientResponse(200, Headers.of("Content-Type", "text/plain; charset=UTF-8"), new byte[0], MAPPER);
        assertThat(typed.hasContentType("text/plain")).isTrue();
        assertThat(typed.hasContentType("text/*")).isTrue();
        assertThat(typed.hasContentType("application/json")).isFalse();
        var response = new ClientResponse(200, Headers.of("X-Codes", "1 2"), new byte[0], MAPPER);
        assertThat(response.hasContentType("text/plain")).isFalse();
        assertThat(response.header("X-Codes", Integer[].class, " ")).containsExactly(1, 2);
        assertThat(response.header("X-Codes", new TypeReference<List<Integer>>() { }, " ")).containsExactly(1, 2);
    }

    @Test
    void validatesWithoutAnInterceptor() {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            webhooks:
              adopted:
                post:
                  operationId: adopted
                  requestBody: {content: {application/json: {schema: {type: object}}}}
                  responses: {'200': {description: ok}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        // Unknown paths are tolerated: requests to them match no operation, so nothing of them is validated.
        var lenient = OpenApiValidator.of(api, OpenApiValidator.Options.DEFAULT.withLevels(LevelPolicy.DEFAULT
            .with(IssueKeys.REQUEST_PATH_MISSING, Level.IGNORE)));
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OpenApiHttpClient(builder.build(), "https://api.example.com", MAPPER, new ClientValidator(lenient, true));
        server.expect(requestTo("https://api.example.com/unknown")).andExpect(content().string("1\n"))
            .andRespond(withSuccess("2\n", MediaType.parseMediaType("application/jsonl")));
        assertThat(client.<List<Integer>>exchange(ClientCall.of("POST", "/unknown").body("application/jsonl", Stream.of(1)), response -> {
            try (var items = response.items(Integer.class)) {
                return items.toList();
            }
        })).containsExactly(2);
        assertThatThrownBy(() -> client.to("https://hooks.example.com").exchange(ClientCall.of("POST", "").operation("adopted")
            .body("application/jsonl", Stream.of(Map.of())), ClientResponse::status))
            .isInstanceOf(ClientValidationException.class).hasMessageContaining("request.contentType.notAllowed");
        server.verify();
    }

    @Test
    void interceptorBuffersResponses() throws Exception {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"),
            "openapi: 3.1.0\ninfo: {title: t, version: v}\npaths:\n  /x:\n    get:\n      responses: {'200': {description: ok}}\n")
            .orElseThrow(d -> new AssertionError(d.toString()));
        var interceptor = new OpenApiClientValidationInterceptor(OpenApiValidator.of(api), true);
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://h/x"));
        var original = new MockClientHttpResponse(new byte[] {1}, HttpStatus.OK);
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
