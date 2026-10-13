package dev.openapifn.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.json.Json;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenApiValidatorTest {

    private static final OpenApiDocument API = OpenApiLoader.defaults().load("classpath:validation/api.yaml")
        .orElseThrow(d -> new AssertionError(d.toString()));
    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(API);
    private static final String ID = UUID.randomUUID().toString();

    private static HttpRequest listPets(String query) {
        return HttpRequest.of("GET", "/v1/pets" + query).withHeader("X-API-Key", "k").withHeader("X-Request-Id", ID);
    }

    private static List<String> issues(ValidationReport report) {
        return report.issues().stream().map(issue -> issue.key() + "@" + issue.location()).toList();
    }

    private static List<String> request(HttpRequest request) {
        return issues(VALIDATOR.validateRequest(request).report());
    }

    private static List<String> response(HttpRequest request, HttpResponse response) {
        return issues(VALIDATOR.validateResponse(request, response));
    }

    @Test
    void routing() {
        assertThat(request(listPets("?limit=5"))).isEmpty();
        assertThat(request(listPets("").withHeader("Host", "x"))).isEmpty();
        assertThat(VALIDATOR.validateRequest(listPets("")).match().orElseThrow().operation().operationId()).contains("listPets");
        assertThat(request(HttpRequest.of("GET", "/v2/pets").withHeader("X-API-Key", "k").withHeader("X-Request-Id", ID))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/pets").withHeader("X-API-Key", "k").withHeader("X-Request-Id", ID))).isEmpty();
        var strict = OpenApiValidator.of(API, OpenApiValidator.Options.DEFAULT.withRequireServerPrefix(true));
        assertThat(issues(strict.validateRequest(HttpRequest.of("GET", "/pets")).report())).containsExactly("request.path.missing@path");
        assertThat(request(HttpRequest.of("GET", "/v1/unknown"))).containsExactly("request.path.missing@path");
        assertThat(request(HttpRequest.of("PATCH", "/v1/pets"))).containsExactly("request.operation.notAllowed@method");
        assertThat(VALIDATOR.validateRequest(HttpRequest.of("GET", "/v1/pets/mine")).match().orElseThrow().operation().operationId())
            .contains("myPets");
        assertThat(request(HttpRequest.of("HEAD", "/v1/pets/mine"))).isEmpty();
        var noHead = OpenApiValidator.of(API, OpenApiValidator.Options.DEFAULT.withHeadFallsBackToGet(false));
        assertThat(issues(noHead.validateRequest(HttpRequest.of("HEAD", "/v1/pets/mine")).report()))
            .containsExactly("request.operation.notAllowed@method");
        assertThat(VALIDATOR.api()).isSameAs(API);
        assertThat(VALIDATOR.options()).isEqualTo(OpenApiValidator.Options.DEFAULT);
    }

    @Test
    void security() {
        assertThat(request(HttpRequest.of("GET", "/v1/pets").withHeader("X-Request-Id", ID))).containsExactly("request.security.missing@security");
        assertThat(request(HttpRequest.of("GET", "/v1/pets").withHeader("X-Request-Id", ID).withHeader("Authorization", "Bearer t"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/pets/1"))).isEmpty();
        assertThat(request(HttpRequest.of("DELETE", "/v1/pets/1").withHeader("Cookie", "token=t"))).containsExactly("request.security.missing@security");
        assertThat(request(HttpRequest.of("DELETE", "/v1/pets/1?key=k").withHeader("Cookie", "token=t"))).isEmpty();
        assertThat(VALIDATOR.validateRequest(HttpRequest.of("DELETE", "/v1/pets/1")).report().issues().getFirst().message())
            .isEqualTo("missing credentials; requires cookieKey + queryKey");
    }

    @Test
    void securitySchemesOfEveryKind() {
        var api = new OpenApiLoader(dev.openapifn.load.ResourceLoaders.inMemory(java.util.Map.of())).parse(java.net.URI.create("mem:/a.yaml"), """
            openapi: 3.1.0
            info: {title: t, version: v}
            paths:
              /basic: {get: {security: [{basic: []}], responses: {'204': {description: ok}}}}
              /oauth: {get: {security: [{oauth: []}], responses: {'204': {description: ok}}}}
              /oidc: {get: {security: [{oidc: []}], responses: {'204': {description: ok}}}}
              /external: {get: {security: [{'https://example.com/schemes#x': []}], responses: {'204': {description: ok}}}}
            components:
              securitySchemes:
                basic: {type: http, scheme: basic}
                oauth: {type: oauth2, flows: {clientCredentials: {tokenUrl: https://t, scopes: {}}}}
                oidc: {type: openIdConnect, openIdConnectUrl: https://o}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var validator = OpenApiValidator.of(api);
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/basic").withHeader("Authorization", "Basic dXNlcjpwdw==")).report().isEmpty()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/basic").withHeader("Authorization", "Bearer t")).report().hasErrors()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/oauth").withHeader("Authorization", "bearer t")).report().isEmpty()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/oauth")).report().hasErrors()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/oidc").withHeader("Authorization", "Bearer t")).report().isEmpty()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/oidc")).report().hasErrors()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/external")).report().isEmpty()).isTrue();
    }

    @Test
    void parameters() {
        assertThat(request(listPets("?limit=500"))).containsExactly("request.parameter.query.schema.maximum@query.limit");
        assertThat(request(listPets("?limit=abc"))).containsExactly("request.parameter.query.schema.type@query.limit");
        assertThat(request(listPets("?limit="))).containsExactly("request.parameter.query.emptyValue@query.limit");
        assertThat(request(listPets("?q="))).isEmpty();
        assertThat(request(listPets("?tags=a,b"))).isEmpty();
        assertThat(VALIDATOR.validateRequest(listPets("?tags=a,b&filter%5Bkind%5D=cat")).parameters())
            .containsEntry("query.tags", Json.of(List.of("a", "b")))
            .containsEntry("query.filter", Json.of(java.util.Map.of("kind", "cat")));
        assertThat(request(listPets("?filter[kind]=cat&filter[age]=x"))).containsExactly("request.parameter.query.schema.type@query.filter/age");
        assertThat(request(listPets("?unknown=1"))).isEmpty();
        var warnUnknown = OpenApiValidator.of(API, OpenApiValidator.Options.DEFAULT.withLevels(LevelPolicy.DEFAULT.with("request.parameter.query.unknown", Level.WARN)));
        var report = warnUnknown.validateRequest(listPets("?unknown=1&filter[kind]=x")).report();
        assertThat(issues(report)).containsExactly("request.parameter.query.unknown@query.unknown");
        assertThat(report.hasErrors()).isFalse();
        assertThat(request(HttpRequest.of("GET", "/v1/pets").withHeader("X-API-Key", "k"))).containsExactly("request.parameter.header.missing@header.X-Request-Id");
        assertThat(request(HttpRequest.of("GET", "/v1/pets").withHeader("X-API-Key", "k").withHeader("X-Request-Id", "nope")))
            .containsExactly("request.parameter.header.schema.format@header.X-Request-Id");
        assertThat(request(listPets("").withHeader("Cookie", "session=ab"))).containsExactly("request.parameter.cookie.schema.minLength@cookie.session");
        assertThat(request(HttpRequest.of("GET", "/v1/pets/abc"))).containsExactly("request.parameter.path.schema.type@path.petId");
        assertThat(request(HttpRequest.of("PUT", "/v1/pets/.5/photo").withBody("text/plain", "abc"))).isEmpty();
        assertThat(request(HttpRequest.of("PUT", "/v1/pets/5/photo").withBody("text/plain", "abc"))).containsExactly("request.parameter.path.invalid@path.petId");
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1;y=2"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=a"))).containsExactly("request.parameter.path.schema.type@path.coords/x");
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1").withHeader("X-Flags", "true, false"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1").withHeader("X-Flags", "yes"))).containsExactly("request.parameter.header.schema.type@header.X-Flags/0");
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1?meta=%7B%22a%22%3A1%7D"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1?meta=%7B%22a%22%3A%22x%22%7D"))).containsExactly("request.parameter.query.schema.type@query.meta/a");
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1?meta=nope"))).containsExactly("request.parameter.query.invalid@query.meta");
        assertThat(request(HttpRequest.of("GET", "/v1/events?%7B%22from%22%3A1%7D"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/events?%7B%7D"))).containsExactly("request.parameter.querystring.schema.required@querystring.filter");
        assertThat(request(HttpRequest.of("GET", "/v1/events"))).isEmpty();
    }

    @Test
    void jsonFormAndXmlBodies() {
        var post = HttpRequest.of("POST", "/v1/pets");
        assertThat(request(post.withBody("application/json", "{\"name\": \"Rex\"}"))).isEmpty();
        assertThat(VALIDATOR.validateRequest(post.withBody("application/json", "{\"name\": \"Rex\"}")).body()).contains(Json.of(java.util.Map.of("name", "Rex")));
        assertThat(request(post.withBody("application/json; charset=utf-8", "{}"))).containsExactly("request.body.schema.required@body");
        assertThat(request(post.withBody("application/json", "{\"id\": 1, \"name\": \"Rex\"}"))).containsExactly("request.body.schema.readOnly@body/id");
        assertThat(request(post.withBody("application/json", "{"))).containsExactly("request.body.invalid@body");
        assertThat(request(post)).containsExactly("request.body.missing@body");
        assertThat(request(post.withBody(new byte[0]))).containsExactly("request.body.missing@body");
        assertThat(request(post.withBody("{}".getBytes(StandardCharsets.UTF_8)))).containsExactly("request.contentType.missing@header.Content-Type");
        assertThat(request(post.withBody("text/csv", "a,b"))).containsExactly("request.contentType.notAllowed@header.Content-Type");
        assertThat(request(post.withBody("nonsense", "a,b"))).containsExactly("request.contentType.invalid@header.Content-Type");
        assertThat(request(post.withBody("application/json; charset=nope", "{}"))).containsExactly("request.body.invalid@body");
        assertThat(request(post.withBody("application/x-www-form-urlencoded", "name=Rex&tags=%5B%22a%22%5D"))).isEmpty();
        assertThat(request(post.withBody("application/x-www-form-urlencoded", "name=Rex&tags=nope"))).containsExactly("request.body.schema.type@body/tags");
        assertThat(request(post.withBody("application/xml", "<pet><name>Rex</name><tags><tags>a</tags></tags></pet>"))).isEmpty();
        assertThat(request(post.withBody("application/xml", "<pet><name></name></pet>"))).containsExactly("request.body.schema.minLength@body/name");
        assertThat(request(post.withBody("application/xml", "<pet>"))).containsExactly("request.body.invalid@body");
        assertThat(request(listPets("").withBody("application/json", "{}"))).containsExactly("request.body.unexpected@body");
        assertThat(VALIDATOR.validateRequest(listPets("").withBody("application/json", "{}")).report().hasErrors()).isFalse();
    }

    @Test
    void multipartAndBinaryBodies() {
        var put = HttpRequest.of("PUT", "/v1/pets/.1/photo");
        var body = """
            --b\r
            Content-Disposition: form-data; name="file"; filename="a.png"\r
            Content-Type: image/png\r
            \r
            PNG\u0001\r
            --b\r
            Content-Disposition: form-data; name="meta"\r
            Content-Type: application/json\r
            \r
            {"name": "Rex"}\r
            --b\r
            Content-Disposition: form-data; name=caption\r
            \r
            hello\r
            --b\r
            Content-Disposition: form-data; name="sizes"\r
            \r
            1\r
            --b\r
            Content-Disposition: form-data; name="sizes"\r
            \r
            2\r
            --b--\r
            """;
        assertThat(request(put.withBody("multipart/form-data; boundary=b", body))).isEmpty();
        assertThat(request(put.withBody("multipart/form-data; boundary=b", body.replace("hello", "hello world, too long").replace("\r\n2\r\n", "\r\nx\r\n"))))
            .containsExactly("request.body.schema.maxLength@body/caption", "request.body.schema.type@body/sizes/1");
        assertThat(request(put.withBody("multipart/form-data; boundary=b", "--b\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nhi\r\n--b--")))
            .containsExactly("request.body.schema.required@body", "request.body.schema.required@body");
        assertThat(request(put.withBody("multipart/form-data", body))).containsExactly("request.body.invalid@body");
        assertThat(request(put.withBody("multipart/form-data; boundary=zz", body))).containsExactly("request.body.invalid@body");
        assertThat(request(put.withHeader("Content-Type", "image/png").withBody(new byte[] {1, 2, 3}))).isEmpty();
        assertThat(request(put.withHeader("Content-Type", "image/png").withBody(new byte[20]))).containsExactly("request.body.schema.maxLength@body");
        assertThat(request(put.withBody("text/plain", "ABC"))).containsExactly("request.body.schema.pattern@body");
    }

    @Test
    void acceptNegotiation() {
        assertThat(request(listPets("").withHeader("Accept", "application/json"))).isEmpty();
        assertThat(request(listPets("").withHeader("Accept", "application/*, text/html;q=0.5"))).isEmpty();
        assertThat(request(listPets("").withHeader("Accept", "*/*"))).isEmpty();
        assertThat(request(listPets("").withHeader("Accept", "text/html"))).containsExactly("request.accept.notAcceptable@header.Accept");
        assertThat(request(listPets("").withHeader("Accept", "application/json;q=0, application/xml;q=0.0"))).containsExactly("request.accept.notAcceptable@header.Accept");
        assertThat(request(HttpRequest.of("GET", "/v1/matrix/;x=1").withHeader("Accept", "image/png"))).isEmpty();
        assertThat(request(HttpRequest.of("GET", "/v1/pets/mine").withHeader("Accept", "text/html"))).isEmpty();
    }

    @Test
    void responses() {
        var list = listPets("");
        var ok = HttpResponse.of(200).withHeader("X-Total", "1");
        assertThat(response(list, ok.withBody("application/json", "[{\"id\": 1, \"name\": \"Rex\"}]"))).isEmpty();
        assertThat(response(list, ok.withHeader("X-Next", "not a uri").withBody("application/json", "[]")))
            .containsExactly("response.header.schema.format@header.X-Next");
        assertThat(response(list, HttpResponse.of(200).withBody("application/json", "[]"))).containsExactly("response.header.missing@header.X-Total");
        assertThat(response(list, HttpResponse.of(200).withHeader("X-Total", "x").withBody("application/json", "[]")))
            .containsExactly("response.header.schema.type@header.X-Total");
        assertThat(response(list, ok.withBody("application/json", "[{\"name\": \"Rex\", \"secret\": \"s\"}]")))
            .containsExactly("response.body.schema.required@body/0", "response.body.schema.writeOnly@body/0/secret");
        assertThat(response(list, ok)).containsExactly("response.body.missing@body");
        assertThat(response(list, ok.withBody("[]".getBytes(StandardCharsets.UTF_8)))).containsExactly("response.contentType.missing@header.Content-Type");
        assertThat(response(list, ok.withBody("text/plain", "[]"))).containsExactly("response.contentType.notAllowed@header.Content-Type");
        assertThat(response(list, ok.withBody("application/json", "["))).containsExactly("response.body.invalid@body");
        assertThat(response(list, ok.withBody("application/xml", "<pets><pet id=\"1\"><name>Rex</name></pet></pets>"))).isEmpty();
        assertThat(response(list, ok.withBody("application/xml", "<pets><pet id=\"x\"><name>Rex</name></pet></pets>")))
            .containsExactly("response.body.schema.type@body/0/id");
        assertThat(response(list, HttpResponse.of(500).withBody("application/problem+json", "{\"message\": 1}")))
            .containsExactly("response.body.schema.type@body/message");
        var post = HttpRequest.of("POST", "/v1/pets").withBody("application/json", "{\"name\": \"Rex\"}");
        assertThat(response(post, HttpResponse.of(418).withBody("application/problem+json", "{\"message\": \"teapot\"}"))).isEmpty();
        assertThat(response(post, HttpResponse.of(302))).containsExactly("response.status.unknown@status");
        assertThat(response(HttpRequest.of("GET", "/v1/pets/mine"), HttpResponse.of(204).withBody("text/plain", "x")))
            .containsExactly("response.body.unexpected@body");
        assertThat(response(HttpRequest.of("GET", "/v1/nowhere"), HttpResponse.of(200))).containsExactly("request.path.missing@path");
        assertThat(response(HttpRequest.of("PATCH", "/v1/pets"), HttpResponse.of(200))).containsExactly("request.operation.notAllowed@method");
        var matrix = HttpRequest.of("GET", "/v1/matrix/;x=1");
        assertThat(response(matrix, HttpResponse.of(200).withBody("text/plain", "12"))).isEmpty();
        assertThat(response(matrix, HttpResponse.of(200).withBody("text/plain", "abc"))).containsExactly("response.body.schema.type@body");
        assertThat(response(matrix, HttpResponse.of(200).withHeader("Content-Type", "application/octet-stream").withBody(new byte[] {(byte) 0xff})))
            .isEmpty();
    }

    @Test
    void sequentialResponses() {
        var events = HttpRequest.of("GET", "/v1/events");
        var ok = HttpResponse.of(200);
        assertThat(response(events, ok.withBody("application/jsonl", "{\"type\": \"a\"}\n\n{\"type\": \"b\"}\n"))).isEmpty();
        assertThat(response(events, ok.withBody("application/jsonl", "{\"type\": \"a\"}\n{}\n"))).containsExactly("response.body.schema.required@body/1");
        assertThat(response(events, ok.withBody("application/jsonl", "{\"type\": \"a\"}\n{\n"))).containsExactly("response.body.invalid@body");
        assertThat(response(events, ok.withBody("application/x-ndjson", "{\"type\": 1}"))).containsExactly("response.body.schema.type@body/0/type");
        assertThat(response(events, ok.withBody("application/json-seq", "\u001e{\"type\": \"a\"}\n\u001e{\"type\": \"b\"}\n"))).isEmpty();
        assertThat(response(events, ok.withBody("text/event-stream", ": comment\nevent: update\ndata: {\"a\":\ndata: 1}\nid: 7\nretry: 100\n\ndata\n\n"))).isEmpty();
        assertThat(response(events, ok.withBody("text/event-stream", "event: e\nretry: soon\n\n")))
            .containsExactly("response.body.schema.required@body/0", "response.body.schema.type@body/0/retry");
        assertThat(response(events, ok.withBody("multipart/mixed; boundary=x",
            "--x\r\nContent-Type: application/json\r\n\r\n{\"type\": \"a\"}\r\n--x\r\nContent-Type: text/plain\r\n\r\nhello\r\n--x--")))
            .containsExactly("response.body.schema.type@body/1");
    }

    @Test
    void webhooksAndCombinedValidation() {
        var adopted = HttpRequest.of("POST", "/anything").withBody("application/json", "{\"name\": \"Rex\"}");
        assertThat(issues(VALIDATOR.validateWebhookRequest("petAdopted", adopted).report())).isEmpty();
        assertThat(issues(VALIDATOR.validateWebhookRequest("petAdopted", adopted.withBody("application/json", "{}")).report()))
            .containsExactly("request.body.schema.required@body");
        assertThat(issues(VALIDATOR.validateWebhookRequest("unknown", adopted).report())).containsExactly("request.path.missing@webhook");
        var list = listPets("?limit=0");
        assertThat(issues(VALIDATOR.validate(list, HttpResponse.of(200).withBody("application/json", "[]"))))
            .containsExactly("request.parameter.query.schema.minimum@query.limit", "response.header.missing@header.X-Total");
        assertThat(issues(VALIDATOR.validate(HttpRequest.of("GET", "/v1/none"), HttpResponse.of(200)))).containsExactly("request.path.missing@path");
    }
}
