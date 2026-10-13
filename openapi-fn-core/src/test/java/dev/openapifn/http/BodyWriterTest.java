package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.MediaType;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Schema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BodyWriterTest {

    private static final OpenApiDocument API = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), """
        openapi: 3.1.0
        info: {title: t, version: v}
        components:
          schemas:
            Order:
              type: object
              xml: {name: order, namespace: 'urn:orders', prefix: o}
              properties:
                id: {type: integer, xml: {attribute: true}}
                note: {type: string, xml: {nodeType: text}}
                lines: {type: array, items: {type: integer, xml: {name: line}}}
                tags: {type: array, xml: {wrapped: true}, items: {type: string, xml: {name: tag}}}
                customer: {type: object, properties: {name: {type: string}}}
                script: {type: string, xml: {nodeType: cdata}}
            List: {type: array, items: {type: integer, xml: {name: n}}}
        """).orElseThrow(d -> new AssertionError(d.toString()));
    private static final SchemaShapes SHAPES = new SchemaShapes(API.schemas());

    private static Schema schema(String name) {
        return API.model().components().schemas().get(name);
    }

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    private static MediaType media(Optional<Schema> schema) {
        return new MediaType(schema, Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of(), Optional.empty(), Map.of());
    }

    @Test
    void xmlRoundTrip() {
        var value = json("{\"id\":1,\"lines\":[1,2],\"tags\":[\"a\",\"b\"],\"customer\":{\"name\":\"<Bob & 'Co'>\"},\"note\":\"hi\"}");
        var text = new XmlWriter(SHAPES).write(value, Optional.of(schema("Order")), "root");
        assertThat(text).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><o:order xmlns:o=\"urn:orders\" id=\"1\"><line>1</line><line>2</line>"
            + "<tags><tag>a</tag><tag>b</tag></tags><customer><name>&lt;Bob &amp; &apos;Co&apos;&gt;</name></customer>hi</o:order>");
        var unprefixed = new XmlWriter(SHAPES).write(json("[1,2]"), Optional.of(schema("List")), "numbers");
        assertThat(unprefixed).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><numbers><n>1</n><n>2</n></numbers>");
        var parsed = new BodyParser(SHAPES).parse(unprefixed.getBytes(StandardCharsets.UTF_8), ContentType.parse("application/xml").orElseThrow(),
            media(Optional.of(schema("List"))));
        assertThat(parsed.toOptional()).contains(json("[1,2]"));
        var cdata = new XmlWriter(SHAPES).write(json("{\"script\":\"a]]>b\"}"), Optional.of(schema("Order")), "root");
        assertThat(cdata).endsWith("<![CDATA[a]]]]><![CDATA[>b]]></o:order>");
        assertThat(new XmlWriter(SHAPES).write(json("{\"a\":\"x\\\"y\"}"), Optional.empty(), "r")).endsWith("<r><a>x&quot;y</a></r>");
        var namespaced = new XmlWriter(SHAPES).write(json("1"), Optional.of(Schema.at(json("{\"xml\":{\"namespace\":\"urn:x\"}}"),
            URI.create("mem:/x"), dev.openapifn.json.JsonPointer.ROOT, URI.create("mem:/x"), dev.openapifn.schema.Dialect.DRAFT_2020_12)), "v");
        assertThat(namespaced).endsWith("<v xmlns=\"urn:x\">1</v>");
        assertThat(new XmlWriter(SHAPES).write(json("{\"list\":[1,2]}"), Optional.empty(), "r")).endsWith("<r><list>1</list><list>2</list></r>");
        assertThat(new XmlWriter(SHAPES).write(json("{\"customer\":[1]}"), Optional.of(schema("Order")), "r"))
            .endsWith("<customer><customer>1</customer></customer></o:order>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/jsonl", "application/x-ndjson", "application/json-seq", "application/xml"})
    void sequencesAndDocumentsRoundTrip(String type) {
        var value = json("[{\"a\":1},{\"b\":[true]}]");
        var contentType = ContentType.parse(type).orElseThrow();
        var schema = Optional.<Schema>empty();
        var bytes = new BodyWriter(SHAPES).write(value, contentType, schema);
        var parsed = new BodyParser(SHAPES).parse(bytes, contentType, media(schema)).toOptional().orElseThrow();
        if (contentType.isXml()) {
            assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("<root><root><a>1</a></root>");
        } else {
            assertThat(parsed).isEqualTo(value);
        }
    }

    @Test
    void textFormsAndEvents() {
        var writer = new BodyWriter(SHAPES);
        assertThat(new String(writer.write(json("42"), ContentType.parse("text/plain").orElseThrow(), Optional.empty()), StandardCharsets.UTF_8))
            .isEqualTo("42");
        var form = writer.write(json("{\"a\":\"x y\",\"b\":[1,2],\"c\":{\"d\":1}}"), ContentType.parse("application/x-www-form-urlencoded").orElseThrow(),
            Optional.empty());
        assertThat(new String(form, StandardCharsets.UTF_8)).isEqualTo("a=x%20y&b=1&b=2&c=%7B%22d%22%3A1%7D");
        assertThat(BodyWriter.form(json("1"))).isEmpty();
        assertThat(BodyWriter.form(json("{\"m\":[[1]]}"))).isEqualTo("m=%5B1%5D");
        var events = writer.write(json("[{\"event\":\"e\",\"data\":\"line1\\nline2\",\"id\":3},\"x\"]"), ContentType.parse("text/event-stream").orElseThrow(),
            Optional.empty());
        assertThat(new String(events, StandardCharsets.UTF_8)).isEqualTo("event: e\nid: 3\ndata: line1\ndata: line2\n\n\n");
        var parsed = new BodyParser(SHAPES).parse(events, ContentType.parse("text/event-stream").orElseThrow(), media(Optional.empty()));
        assertThat(parsed.toOptional()).contains(json("[{\"event\":\"e\",\"id\":\"3\",\"data\":\"line1\\nline2\"}]"));
    }

    @Test
    void multipartRoundTrip() {
        var parts = List.of(
            BodyWriter.part("file", Optional.of("a.png"), Optional.of("image/png"), new byte[] {1, 2}),
            BodyWriter.part("meta", Optional.empty(), Optional.of("application/json"), "{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
            BodyWriter.part("text", Optional.empty(), Optional.empty(), "hello".getBytes(StandardCharsets.UTF_8)),
            new Multipart.Part(Headers.of("Content-Disposition", "form-data; name=x", "X-Custom", "1"), new byte[0]));
        var body = BodyWriter.multipart(parts);
        assertThat(body.contentType()).startsWith("multipart/form-data; boundary=openapi-fn-");
        var parsed = Multipart.parse(body.body(), ContentType.parse(body.contentType()).orElseThrow().parameter("boundary").orElseThrow())
            .orElseThrow(AssertionError::new);
        assertThat(parsed).extracting(part -> part.name().orElseThrow()).containsExactly("file", "meta", "text", "x");
        assertThat(parsed.getFirst().filename()).contains("a.png");
        assertThat(parsed.get(3).headers().first("x-custom")).contains("1");
        assertThat(new String(parsed.get(2).body(), StandardCharsets.UTF_8)).isEqualTo("hello");
    }
}
