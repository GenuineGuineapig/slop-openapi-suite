package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.MediaType;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Schema;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            Form: {type: object, properties: {n: {type: integer}, tags: {type: array, items: {type: string}}, meta: {type: object}}}
            Tuple: {type: array, prefixItems: [{type: integer}, {type: boolean}], items: {type: number}}
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
        assertThat(new String(form, StandardCharsets.UTF_8)).isEqualTo("a=x+y&b=1&b=2&c=%7B%22d%22%3A1%7D");
        assertThat(BodyWriter.form(json("1"))).isEmpty();
        assertThat(BodyWriter.form(json("{\"m\":[[1]]}"))).isEqualTo("m=%5B1%5D");
        var events = writer.write(json("[{\"event\":\"e\",\"data\":\"line1\\nline2\",\"id\":3},\"x\"]"), ContentType.parse("text/event-stream").orElseThrow(),
            Optional.empty());
        assertThat(new String(events, StandardCharsets.UTF_8)).isEqualTo("event: e\nid: 3\ndata: line1\ndata: line2\n\n\n");
        var parsed = new BodyParser(SHAPES).parse(events, ContentType.parse("text/event-stream").orElseThrow(), media(Optional.empty()));
        assertThat(parsed.toOptional()).contains(json("[{\"event\":\"e\",\"id\":\"3\",\"data\":\"line1\\nline2\"}]"));
    }

    @Test
    void writesItemsOneByOne() {
        var jsonl = ContentType.parse("application/jsonl").orElseThrow();
        assertThat(new String(new BodyWriter(SHAPES).write(json("{\"a\":1}"), jsonl, Optional.empty()), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}\n");
        assertThat(BodyWriter.item(json("[1]"), jsonl)).asString(StandardCharsets.UTF_8).isEqualTo("[1]\n");
        assertThat(BodyWriter.item(json("1"), ContentType.parse("application/json-seq").orElseThrow())).asString(StandardCharsets.UTF_8)
            .isEqualTo("\u001e1\n");
        assertThat(BodyWriter.item(json("{\"data\":\"d\"}"), ContentType.parse("text/event-stream").orElseThrow())).asString(StandardCharsets.UTF_8)
            .isEqualTo("data: d\n\n");
        assertThat(BodyWriter.end(jsonl)).isEmpty();
        var mixed = ContentType.parse("multipart/mixed; boundary=m").orElseThrow();
        var body = new ByteArrayOutputStream();
        body.writeBytes(BodyWriter.item(json("{\"a\":1}"), mixed));
        body.writeBytes(BodyWriter.part("m", BodyWriter.part("text/plain", "x".getBytes(StandardCharsets.UTF_8))));
        body.writeBytes(BodyWriter.end(mixed));
        assertThat(body.toString(StandardCharsets.UTF_8)).isEqualTo("--m\r\nContent-Type: application/json\r\n\r\n{\"a\":1}\r\n"
            + "--m\r\nContent-Type: text/plain\r\n\r\nx\r\n--m--\r\n");
        assertThat(new BodyParser(SHAPES).parse(body.toByteArray(), mixed, media(Optional.empty())).toOptional()).contains(json("[{\"a\":1},\"x\"]"));
        var parser = new BodyParser(SHAPES);
        var frame = new ItemFramer.Frame(new byte[0], "1".getBytes(StandardCharsets.UTF_8), Optional.empty(), false);
        assertThat(parser.item(frame, ContentType.parse("application/jsonl; charset=nope").orElseThrow(), media(Optional.empty()), 0).failure())
            .contains("unsupported charset 'nope'");
        assertThat(parser.item(frame, jsonl, media(Optional.empty()), 0).toOptional()).contains(Optional.of(json("1")));
    }

    @Test
    void writesMultipartValues() {
        var writer = new BodyWriter(SHAPES);
        var parser = new BodyParser(SHAPES);
        var form = ContentType.parse("multipart/form-data; boundary=b").orElseThrow();
        var formBody = writer.write(json("{\"n\":1,\"tags\":[\"a\",\"b\"],\"meta\":{\"x\":[1]}}"), form, Optional.empty());
        assertThat(new String(formBody, StandardCharsets.UTF_8)).startsWith("--b\r\nContent-Disposition: form-data; name=\"n\"\r\nContent-Type: text/plain\r\n\r\n1\r\n")
            .endsWith("--b--\r\n");
        assertThat(parser.parse(formBody, form, media(Optional.of(schema("Form")))).toOptional())
            .contains(json("{\"n\":1,\"tags\":[\"a\",\"b\"],\"meta\":{\"x\":[1]}}"));
        assertThat(parser.parse(writer.write(json("[1]"), form, Optional.empty()), form, media(Optional.empty())).toOptional()).contains(json("{}"));

        var mixed = ContentType.parse("multipart/mixed; boundary=m").orElseThrow();
        var tuple = writer.write(json("[1,true,2.5,{\"a\":1},[2]]"), mixed, Optional.empty());
        assertThat(parser.parse(tuple, mixed, media(Optional.of(schema("Tuple")))).toOptional()).contains(json("[1,true,2.5,{\"a\":1},[2]]"));
        var encoded = new MediaType(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of(),
            List.of(new dev.openapifn.model.Encoding(Optional.of("application/json"), Map.of(), Optional.empty(), Optional.empty(), false, Map.of(),
                List.of(), Optional.empty(), Map.of())),
            Optional.of(new dev.openapifn.model.Encoding(Optional.of("text/csv"), Map.of(), Optional.empty(), Optional.empty(), false, Map.of(),
                List.of(), Optional.empty(), Map.of())), Map.of());
        var unlabelled = "--m\r\n\r\n[1]\r\n--m\n\na,b\r\n--m--\r\n";
        assertThat(parser.parse(unlabelled.getBytes(StandardCharsets.UTF_8), mixed, encoded).toOptional()).contains(json("[[1],\"a,b\"]"));
        var items = new MediaType(Optional.empty(), Optional.of(schema("List").subschema("items").orElseThrow()), Optional.empty(), Map.of(), Map.of(),
            List.of(), Optional.empty(), Map.of());
        assertThat(parser.parse(writer.write(json("7"), mixed, Optional.empty()), mixed, items).toOptional()).contains(json("[7]"));

        assertThatThrownBy(() -> writer.write(json("[]"), ContentType.parse("multipart/mixed").orElseThrow(), Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("multipart content type has no boundary");
        var positional = BodyWriter.multipart("related", List.of(BodyWriter.part("image/png", new byte[] {1}),
            BodyWriter.part(Optional.empty(), json("\"x\""))));
        assertThat(positional.contentType()).startsWith("multipart/related; boundary=openapi-fn-");
        assertThat(new String(positional.body(), StandardCharsets.UTF_8)).contains("Content-Type: image/png", "Content-Type: text/plain\r\n\r\nx\r\n");
        assertThat(SHAPES.item(schema("Tuple"), 1).flatMap(Schema::format)).isEmpty();
        assertThat(SHAPES.item(schema("Tuple"), 1).map(s -> s.types())).contains(Set.of("boolean"));
        assertThat(SHAPES.item(schema("Tuple"), 5).map(s -> s.types())).contains(Set.of("number"));
        assertThat(SHAPES.item(schema("Form"), 0)).isEmpty();
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
