package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpPrimitivesTest {

    @Test
    void headers() {
        var headers = Headers.of("Accept", "a", "accept", "b", "X-One", "1", "dangling");
        assertThat(headers.all("ACCEPT")).containsExactly("a", "b");
        assertThat(headers.first("x-one")).contains("1");
        assertThat(headers.first("missing")).isEmpty();
        assertThat(headers.has("X-ONE")).isTrue();
        assertThat(headers.has("dangling")).isFalse();
        assertThat(new Headers(Map.of("A", List.of("1"))).all("a")).containsExactly("1");
    }

    @Test
    void requestsAndResponses() {
        var request = HttpRequest.of("get", "/a?b=1").withBody("application/json", "{}");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/a");
        assertThat(request.query()).isEqualTo("b=1");
        assertThat(request.contentType()).contains("application/json");
        assertThat(new String(request.body().orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(HttpRequest.of("GET", "/a").query()).isEmpty();
        assertThatThrownBy(() -> new HttpRequest("GET", null, "", Headers.EMPTY, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpRequest("GET", "/", null, Headers.EMPTY, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpRequest("GET", "/", "", null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpRequest("GET", "/", "", Headers.EMPTY, null)).isInstanceOf(NullPointerException.class);
        var response = HttpResponse.of(201).withBody("text/plain", "x");
        assertThat(response.contentType()).contains("text/plain");
        assertThatThrownBy(() -> new HttpResponse(200, null, Optional.empty())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpResponse(200, Headers.EMPTY, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contentTypes() {
        var json = ContentType.parse("Application/JSON; Charset=\"UTF-8\"; broken; x=").orElseThrow();
        assertThat(json.essence()).isEqualTo("application/json");
        assertThat(json.parameter("CHARSET")).contains("UTF-8");
        assertThat(json.isJson()).isTrue();
        assertThat(json).hasToString("application/json; charset=UTF-8; x=");
        assertThat(ContentType.parse("application/problem+json").orElseThrow().isJson()).isTrue();
        assertThat(ContentType.parse("text/xml").orElseThrow().isXml()).isTrue();
        assertThat(ContentType.parse("application/atom+xml").orElseThrow().isXml()).isTrue();
        assertThat(ContentType.parse("text/plain").orElseThrow().isXml()).isFalse();
        assertThat(ContentType.parse("text/plain; a=\"").orElseThrow().parameter("a")).contains("\"");
        assertThat(ContentType.parse("text/plain; a=\"x").orElseThrow().parameter("a")).contains("\"x");
        assertThat(ContentType.parse("nonsense")).isEmpty();
        assertThat(ContentType.parse("/json")).isEmpty();
        assertThat(ContentType.parse("text/")).isEmpty();
        assertThat(ContentType.parse("a/b/c")).isEmpty();
        var actual = ContentType.parse("application/vnd.api+json; charset=utf-8").orElseThrow();
        assertThat(ContentType.bestMatch(List.of("*/*", "application/*", "application/*+json", "application/vnd.api+json", "bad"), actual))
            .contains("application/vnd.api+json");
        assertThat(ContentType.bestMatch(List.of("*/*", "application/*"), actual)).contains("application/*");
        assertThat(ContentType.bestMatch(List.of("text/*", "application/*+xml"), actual)).isEmpty();
        assertThat(ContentType.bestMatch(List.of("application/vnd.api+json; charset=latin1"), actual)).isEmpty();
        assertThat(ContentType.bestMatch(List.of("application/vnd.api+json; charset=UTF-8", "application/vnd.api+json"), actual))
            .contains("application/vnd.api+json; charset=UTF-8");
    }

    @Test
    void urlCodec() {
        assertThat(UrlCodec.decode("a%20b+c", false)).isEqualTo("a b+c");
        assertThat(UrlCodec.decode("a%20b+c", true)).isEqualTo("a b c");
        assertThat(UrlCodec.decode("plain", true)).isEqualTo("plain");
        assertThat(UrlCodec.decode("a+b", false)).isEqualTo("a+b");
        assertThat(UrlCodec.decode("%C3%A9%zz%4", false)).isEqualTo("é%zz%4");
        assertThat(UrlCodec.decode("%4g", false)).isEqualTo("%4g");
        assertThat(UrlCodec.encode("a b/é", "/")).isEqualTo("a%20b/%C3%A9");
        assertThat(UrlCodec.parseQuery("a=1&&b&c=x+y&d=%3D")).containsExactly(Map.entry("a", "1"), Map.entry("b", ""), Map.entry("c", "x y"),
            Map.entry("d", "="));
        assertThat(UrlCodec.parseCookies(List.of("a=1; b = 2", "c=3;broken;=x"))).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"),
            Map.entry("c", "3"));
    }

    @Test
    void pathTemplates() {
        var template = PathTemplate.compile("/files/{name}.{ext}/{rest}");
        assertThat(template.names()).containsExactly("name", "ext", "rest");
        assertThat(template.match("/files/a.b.c/x")).contains(Map.of("name", "a", "ext", "b.c", "rest", "x"));
        assertThat(template.match("/files/a/x")).isEmpty();
        assertThat(template.template()).isEqualTo("/files/{name}.{ext}/{rest}");
        assertThat(template).hasToString("/files/{name}.{ext}/{rest}");
        var prefix = PathTemplate.compile("/api/{version}/");
        assertThat(prefix.stripPrefix("/api/v1/pets")).contains("/pets");
        assertThat(prefix.stripPrefix("/api/v1")).contains("/");
        assertThat(prefix.stripPrefix("/api/v1x")).contains("/");
        assertThat(PathTemplate.compile("/api").stripPrefix("/apix")).isEmpty();
        assertThat(prefix.stripPrefix("/other")).isEmpty();
        var concrete = PathTemplate.compile("/pets/mine");
        var templated = PathTemplate.compile("/pets/{id}");
        assertThat(concrete.compareSpecificity(templated)).isNegative();
        assertThat(PathTemplate.compile("/a/b/{x}").compareSpecificity(PathTemplate.compile("/a/{x}"))).isNegative();
        assertThat(templated.compareSpecificity(templated)).isZero();
    }

    @Test
    void multipart() {
        var body = "preamble\r\n--b  \r\nContent-Disposition: form-data; name=a; filename=\"f.txt\"\r\nContent-Type: text/plain\r\n\r\nA\r\n--b\nContent-Disposition: form-data; name=\"b\"\n\nB\n--b--\r\n";
        var parts = Multipart.parse(body.getBytes(StandardCharsets.ISO_8859_1), "b").orElseThrow(AssertionError::new);
        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst().name()).contains("a");
        assertThat(parts.getFirst().filename()).contains("f.txt");
        assertThat(parts.getFirst().contentType()).contains("text/plain");
        assertThat(new String(parts.getFirst().body(), StandardCharsets.ISO_8859_1)).isEqualTo("A");
        assertThat(new String(parts.get(1).body(), StandardCharsets.ISO_8859_1)).isEqualTo("B");
        assertThat(parts.get(1).filename()).isEmpty();
        assertThat(new Multipart.Part(Headers.EMPTY, new byte[0]).name()).isEmpty();
        assertThat(new Multipart.Part(Headers.of("Content-Disposition", "form-data"), new byte[0]).name()).isEmpty();
        assertThat(Multipart.parse("--b\r\nX: y\r\n\r\nz--b--".getBytes(StandardCharsets.ISO_8859_1), "b").orElseThrow(AssertionError::new)).hasSize(1);
        assertThat(Multipart.parse("nothing".getBytes(StandardCharsets.ISO_8859_1), "b").failure()).contains("multipart body does not contain the boundary 'b'");
        assertThat(Multipart.parse("--b\r\nX: y\r\n\r\nz".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart body is not terminated by the closing boundary");
        assertThat(Multipart.parse("--b\r\nno separator\r\n--b--".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart part has no header section");
        assertThat(Multipart.parse("--b\r\nbad header\r\n\r\nz\r\n--b--".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("malformed multipart header 'bad header'");
        assertThat(Multipart.parse("--b\r\n--b--".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart part has no header section");
        assertThat(Multipart.parse("--b".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart body is not terminated by the closing boundary");
        assertThat(Multipart.parse("--b-x".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart body is not terminated by the closing boundary");
        assertThat(Multipart.parse("--b\rX: y\n\nz\n--b--".getBytes(StandardCharsets.ISO_8859_1), "b").orElseThrow(AssertionError::new)
            .getFirst().headers().first("X")).contains("y");
        assertThat(Multipart.parse("--b-".getBytes(StandardCharsets.ISO_8859_1), "b").failure())
            .contains("multipart body is not terminated by the closing boundary");
        assertThat(Multipart.parse("--b\t\nX: y\n\nz\n--b--".getBytes(StandardCharsets.ISO_8859_1), "b").orElseThrow(AssertionError::new)).hasSize(1);
    }
}
