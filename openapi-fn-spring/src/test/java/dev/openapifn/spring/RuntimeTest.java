package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.servlet.OpenApiExceptionHandler;
import dev.openapifn.spring.servlet.ServletRuntime;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class RuntimeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final OpenApiRuntime RUNTIME = runtime(true);
    private static final ServletRuntime SERVLET = new ServletRuntime(RUNTIME);

    private static OpenApiRuntime runtime(boolean failOnInvalid) {
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            paths:
              /items/{id}:
                get:
                  operationId: getItem
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}}
                    - {name: q, in: query, schema: {type: integer}}
                  responses:
                    '200':
                      description: ok
                      headers:
                        X-Map: {explode: true, schema: {type: object}}
                      content:
                        '*/*': {schema: {type: string}}
                    '204': {description: none}
                post:
                  operationId: postItem
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer}}
                  requestBody:
                    content:
                      application/json: {schema: {type: object}}
                      application/xml: {schema: {type: object}}
                      text/plain: {schema: {type: string}}
                      multipart/form-data: {schema: {type: object}}
                  responses:
                    '200': {description: ok}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        return new OpenApiRuntime("items", api, OpenApiValidator.of(api), MAPPER, failOnInvalid);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.setQueryString(uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : null);
        request.setRequestURI(uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri);
        return request;
    }

    record Item(int status, @Nullable Object body, Map<String, @Nullable Object> headers, @Nullable String contentType) implements ApiResponse {
    }

    @Test
    void accessorsAndUnknownOperations() {
        assertThat(RUNTIME.name()).isEqualTo("items");
        assertThat(RUNTIME.api().operations()).hasSize(2);
        assertThat(RUNTIME.validator().api()).isSameAs(RUNTIME.api());
        assertThat(RUNTIME.mapper()).isSameAs(MAPPER);
        assertThat(RUNTIME.failsOnInvalidRequests()).isTrue();
        assertThat(SERVLET.runtime()).isSameAs(RUNTIME);
        assertThatThrownBy(() -> RUNTIME.operation("nope")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("API 'items' has no operation 'nope'");
        var runtimes = new OpenApiRuntimes(List.of(RUNTIME));
        assertThatThrownBy(() -> runtimes.get("other")).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No OpenAPI description named 'other'; known: [items]");
        assertThat(runtimes.routing(dev.openapifn.http.HttpRequest.of("GET", "/items/1"))).contains(RUNTIME);
        assertThat(runtimes.routing(dev.openapifn.http.HttpRequest.of("GET", "/other"))).isEmpty();
    }

    @Test
    void bindsWithoutTheFilter() {
        var withPattern = request("GET", "/items/5?q=2");
        withPattern.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/items/{id}");
        var binding = SERVLET.bind(withPattern, "getItem");
        assertThat(binding.parameter("path", "id", Long.class)).isEqualTo(5L);
        assertThat(binding.parameter("query", "q", new TypeReference<Integer>() { })).isEqualTo(2);
        assertThat(binding.parameter("query", "missing", String.class)).isNull();
        assertThat(binding.validation().report().isEmpty()).isTrue();
        assertThat(SERVLET.bind(request("GET", "/items/6"), "getItem").parameter("path", "id", Integer.class)).isEqualTo(6);
        var mismatched = request("GET", "/items/7");
        mismatched.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/other/{x}");
        assertThat(SERVLET.bind(mismatched, "postItem").validation().parameters()).containsOnlyKeys("path.id");
        var unroutable = request("GET", "/nowhere");
        assertThat(new ServletRuntime(runtime(false)).bind(unroutable, "postItem").validation().parameters()).isEmpty();
        assertThatThrownBy(() -> SERVLET.bind(request("GET", "/items/x"), "getItem")).isInstanceOf(OpenApiValidationException.class);
        assertThat(new ServletRuntime(runtime(false)).bind(request("GET", "/items/x"), "getItem").validation().report().hasErrors()).isTrue();
        var cached = request("GET", "/items/8");
        cached.setAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE, RUNTIME.validator().validateRequest(dev.openapifn.http.HttpRequest.of("GET", "/items/9")));
        assertThat(SERVLET.bind(cached, "getItem").parameter("path", "id", Integer.class)).isEqualTo(9);
        cached.setAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE, RUNTIME.validator().validateRequest(dev.openapifn.http.HttpRequest.of("POST", "/items/9")));
        assertThat(SERVLET.bind(cached, "getItem").parameter("path", "id", Integer.class)).isEqualTo(8);
        cached.setAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE, "not a validation");
        assertThat(SERVLET.bind(cached, "getItem").parameter("path", "id", Integer.class)).isEqualTo(8);
    }

    @Test
    void bindsBodies() {
        var json = request("POST", "/items/1");
        json.setContentType("application/json");
        json.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(SERVLET.bind(json, "postItem").body(new TypeReference<Map<String, Integer>>() { })).containsEntry("a", 1);
        var xml = request("POST", "/items/1");
        xml.setContentType("application/xml");
        xml.setContent("<r><a>1</a></r>".getBytes(StandardCharsets.UTF_8));
        var xmlBinding = SERVLET.bind(xml, "postItem");
        assertThat(xmlBinding.body(new TypeReference<Map<String, String>>() { })).containsEntry("a", "1");
        assertThat(xmlBinding.body(String.class)).isEqualTo("<r><a>1</a></r>");
        var text = request("POST", "/items/1");
        text.setContentType("text/plain");
        text.setContent("hi".getBytes(StandardCharsets.UTF_8));
        assertThat(SERVLET.bind(text, "postItem").body(String.class)).isEqualTo("hi");
        var empty = request("POST", "/items/1");
        assertThat(SERVLET.bind(empty, "postItem").body(String.class)).isNull();
        var unparseable = request("POST", "/items/1");
        unparseable.setContentType("application/json");
        unparseable.setContent("{".getBytes(StandardCharsets.UTF_8));
        assertThat(new ServletRuntime(runtime(false)).bind(unparseable, "postItem").body(Object.class)).isNull();
        var wrongType = request("POST", "/items/1");
        wrongType.setContentType("application/json");
        wrongType.setContent("{\"a\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> SERVLET.bind(wrongType, "postItem").body(new TypeReference<Map<String, Integer>>() { }))
            .isInstanceOfSatisfying(OpenApiValidationException.class, e -> {
                assertThat(e.report().issues()).singleElement().satisfies(issue -> assertThat(issue.key()).isEqualTo("request.binding"));
                assertThat(e.status()).isEqualTo(400);
            });
        var multipart = request("POST", "/items/1");
        multipart.setContentType("multipart/form-data; boundary=b");
        multipart.setContent(("--b\r\nContent-Disposition: form-data; name=a\r\n\r\nx\r\n--b\r\nContent-Disposition: form-data; name=f; filename=f.bin\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n1\r\n--b\r\nContent-Disposition: form-data; name=j\r\nContent-Type: application/json\r\n\r\n[1]\r\n"
            + "--b\r\nContent-Disposition: form-data; name=g; filename=g.bin\r\n\r\n2\r\n--b\r\nContent-Disposition: form-data; name=g; filename=h.bin\r\n\r\n3\r\n"
            + "--b\r\nContent-Disposition: form-data; name=o\r\nContent-Type: application/octet-stream\r\n\r\n4\r\n--b\r\nContent-Disposition: form-data; name=t\r\nContent-Type: text/plain\r\n\r\n5\r\n--b--").getBytes(StandardCharsets.ISO_8859_1));
        var parts = SERVLET.bind(multipart, "postItem").body(new TypeReference<Map<String, Object>>() { });
        assertThat(parts).containsEntry("a", "x").containsEntry("j", List.of(1));
        assertThat(parts.get("f")).isEqualTo(new FilePart("f.bin", "application/octet-stream", "1".getBytes(StandardCharsets.UTF_8)));
        assertThat(parts.get("g")).isEqualTo(new FilePart("g.bin", "application/octet-stream", "2".getBytes(StandardCharsets.UTF_8)));
        assertThat(parts.get("o")).isEqualTo(new FilePart(null, "application/octet-stream", "4".getBytes(StandardCharsets.UTF_8)));
        assertThat(parts).containsEntry("t", "5");
    }

    @Test
    void respondsWithHeadersAndNegotiatedTypes() {
        var request = request("GET", "/items/1");
        var headers = new LinkedHashMap<String, @Nullable Object>();
        headers.put("X-Map", Map.of("a", 1));
        headers.put("X-Plain", "v");
        headers.put("X-None", null);
        var entity = SERVLET.respond(request, "getItem", new Item(200, "text", headers, null));
        assertThat(entity.getHeaders().getFirst("X-Map")).isEqualTo("a=1");
        assertThat(entity.getHeaders().getFirst("X-Plain")).isEqualTo("v");
        assertThat(entity.getHeaders().containsHeader("X-None")).isFalse();
        assertThat(entity.getHeaders().getContentType()).hasToString("application/octet-stream");
        assertThat(new String((byte[]) entity.getBody(), StandardCharsets.UTF_8)).isEqualTo("text");
        var file = SERVLET.respond(request, "getItem", new Item(200, FilePart.of("f.png", "image/png", new byte[] {1}), Map.of(), null));
        var accepting = request("GET", "/items/1");
        accepting.addHeader("Accept", "image/png");
        assertThat(SERVLET.respond(accepting, "getItem", new Item(200, new byte[] {1}, Map.of(), null)).getHeaders().getContentType())
            .hasToString("application/octet-stream");
        assertThat(file.getHeaders().getContentType()).hasToString("image/png");
        var bytes = SERVLET.respond(request, "getItem", new Item(200, new byte[] {2}, Map.of(), "application/pdf"));
        assertThat((byte[]) bytes.getBody()).containsExactly(2);
        var undescribed = SERVLET.respond(request, "getItem", new Item(299, Map.of("a", 1), Map.of(), null));
        assertThat(undescribed.getHeaders().getContentType()).hasToString("application/json");
        assertThat(SERVLET.respond(request, "getItem", new Item(204, null, Map.of(), null)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var mixed = SERVLET.respond(request, "getItem", new Item(200, List.of(Map.of("a", 1), "x"), Map.of(), "multipart/mixed"));
        var boundary = mixed.getHeaders().getContentType().getParameter("boundary");
        assertThat(boundary).startsWith("openapi-fn-");
        assertThat(new String((byte[]) mixed.getBody(), StandardCharsets.UTF_8)).isEqualTo("--" + boundary + "\r\nContent-Type: application/json\r\n\r\n"
            + "{\"a\":1}\r\n--" + boundary + "\r\nContent-Type: text/plain\r\n\r\nx\r\n--" + boundary + "--\r\n");
        var bounded = SERVLET.respond(request, "getItem", new Item(200, List.of("y"), Map.of(), "multipart/mixed; boundary=fixed"));
        assertThat(bounded.getHeaders().getContentType()).hasToString("multipart/mixed;boundary=fixed");
        assertThatThrownBy(() -> SERVLET.respond(request, "getItem", new Item(200, "x", Map.of(), "nonsense")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid media type 'nonsense'");
        ApiResponse minimal = () -> 202;
        assertThat(minimal.body()).isNull();
        assertThat(minimal.headers()).isEmpty();
        assertThat(minimal.contentType()).isNull();
    }

    @Test
    void jacksonTrees() {
        JsonValue value = Json.parse("{\"o\":{\"a\":[1,1.5,\"s\",true,null]}}").orElseThrow(e -> new AssertionError(e));
        var node = JacksonJson.toJsonNode(value);
        assertThat(JacksonJson.toJsonValue(node)).isEqualTo(value);
        var factory = JsonNodeFactory.instance;
        assertThat(JacksonJson.toJsonValue(factory.binaryNode(new byte[] {1, 2}))).isEqualTo(Json.of("AQI="));
        assertThat(JacksonJson.toJsonValue(factory.missingNode())).isEqualTo(Json.of(null));
        assertThat(JacksonJson.toJsonValue(JacksonJson.embed(new Object()))).isEqualTo(Json.of(null));
        assertThat(JacksonJson.toJsonValue(factory.numberNode(new BigDecimal("2.50")))).isEqualTo(Json.of(2.5));
        assertThat(MAPPER.treeToValue(JacksonJson.toJsonNode(Json.of(List.of(1, 5_000_000_000L, new BigInteger("99999999999999999999")))), Object.class))
            .isEqualTo(List.of(1, 5_000_000_000L, new BigInteger("99999999999999999999")));
    }

    @Test
    void filePartsProblemsAndExceptions() {
        var part = FilePart.of("a.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));
        assertThat(part.text()).isEqualTo("hi");
        assertThat(part.size()).isEqualTo(2);
        assertThat(part).hasToString("FilePart[filename=a.txt, contentType=text/plain, size=2]");
        assertThat(part).isEqualTo(FilePart.of("a.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8))).hasSameHashCodeAs(
            FilePart.of("a.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8)));
        assertThat(part).isNotEqualTo(FilePart.of("b.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo(FilePart.of("a.txt", "text/html", "hi".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo(FilePart.of("a.txt", "text/plain", "ho".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo("a.txt");
        assertThat(FilePart.of(new byte[] {1}).contentType()).isEqualTo(FilePart.OCTET_STREAM);
        var content = new byte[] {7};
        var copy = FilePart.of(content);
        content[0] = 8;
        assertThat(copy.content()).containsExactly(7);
        assertThatThrownBy(() -> new FilePart(null, null, new byte[0])).isInstanceOf(NullPointerException.class);
        var report = new ValidationReport(List.of(new ValidationIssue("request.security.missing", Level.ERROR, "m", "security")));
        assertThat(new OpenApiValidationException(report).status()).isEqualTo(401);
        assertThat(new OpenApiValidationException(ValidationReport.EMPTY).status()).isEqualTo(400);
        assertThat(Problems.of(599, "d", "/i", report).string("title")).contains("Error");
        var response = new OpenApiExceptionHandler().handle(new OpenApiValidationException(report), request("GET", "/x"));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo(Problems.MEDIA_TYPE);
        assertThat(response.getBody()).contains("\"instance\":\"/x\"");
    }

    @Test
    void verifiersCanBeAddedPerRequest() {
        var runtime = runtime(true);
        var verifying = runtime.withVerifier(credential -> Optional.of(dev.openapifn.fn.Result.err("nope")));
        assertThat(verifying.name()).isEqualTo(runtime.name());
        assertThat(verifying.api()).isSameAs(runtime.api());
        assertThat(verifying.mapper()).isSameAs(runtime.mapper());
        assertThat(verifying.validator().options().verifier().verify(new dev.openapifn.security.Credential.ApiKey("key", "k")))
            .hasValueSatisfying(result -> assertThat(result.failure()).contains("nope"));
        assertThat(runtime.validator().options().verifier().verify(new dev.openapifn.security.Credential.ApiKey("key", "k"))).isEmpty();
    }
}
