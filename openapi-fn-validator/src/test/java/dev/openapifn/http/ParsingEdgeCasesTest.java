package dev.openapifn.http;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.Encoding;
import dev.openapifn.model.MediaType;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Schema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ParsingEdgeCasesTest {

    private static OpenApiDocument api(String path, String parameters, String components) {
        var yaml = """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              %s:
                get:
                  parameters: %s
                  responses: {'200': {description: ok}}
            components:
              schemas: %s
            """.formatted(path, parameters, components);
        return new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), yaml)
            .orElseThrow(d -> new AssertionError(d.toString()));
    }

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    private record Fixture(OpenApiDocument api, Parameter parameter, ParameterParser parser) {
        Optional<JsonValue> parse(HttpRequest request, Map<String, String> path) {
            return parser.parse(parameter, ParameterParser.Sources.of(request, path), Set.of("other")).toOptional().orElseThrow();
        }

        String error(HttpRequest request, Map<String, String> path) {
            return parser.parse(parameter, ParameterParser.Sources.of(request, path), Set.of()).failure().orElseThrow();
        }
    }

    private static Fixture fixture(String path, String parameter) {
        var api = api(path, "[" + parameter + "]", "{Named: {type: object, properties: {a: {type: integer}}, additionalProperties: {type: integer}}}");
        return new Fixture(api, api.operations().getFirst().parameters().getFirst(), new ParameterParser(new SchemaShapes(api.schemas())));
    }

    private static final HttpRequest GET = HttpRequest.of("GET", "/p");

    @Test
    void pathStyles() {
        var label = fixture("/p/{x}", "{name: x, in: path, required: true, style: label, explode: true, schema: {type: object, properties: {a: {type: integer}}}}");
        assertThat(label.parse(GET, Map.of("x", ".a=1.b"))).contains(json("{\"a\":1,\"b\":\"\"}"));
        var labelObject = fixture("/p/{x}", "{name: x, in: path, required: true, style: label, schema: {type: object}}");
        assertThat(labelObject.parse(GET, Map.of("x", ".a,1,b"))).contains(json("{\"a\":\"1\",\"b\":\"\"}"));
        var matrix = fixture("/p/{x}", "{name: x, in: path, required: true, style: matrix, schema: {type: string}}");
        assertThat(matrix.parse(GET, Map.of("x", ";x"))).contains(json("\"\""));
        assertThat(matrix.error(GET, Map.of("x", "x=1"))).isEqualTo("matrix style value must start with ';'");
        assertThat(matrix.error(GET, Map.of("x", ";"))).isEqualTo("matrix style value must name parameter 'x'");
        assertThat(matrix.error(GET, Map.of("x", ";y=1"))).isEqualTo("matrix style value must name parameter 'x'");
        assertThat(matrix.parse(GET, Map.of())).isEmpty();
        var simple = fixture("/p/{x}", "{name: x, in: path, required: true, schema: {type: array, items: {type: integer}}}");
        assertThat(simple.parse(GET, Map.of("x", ""))).contains(json("[]"));
        assertThat(simple.parse(GET, Map.of("x", "1,%32"))).contains(json("[1,2]"));
        var untyped = fixture("/p/{x}", "{name: x, in: path, required: true}");
        assertThat(untyped.parse(GET, Map.of("x", "5"))).contains(json("\"5\""));
    }

    @Test
    void contentParameters() {
        var path = fixture("/p/{x}", "{name: x, in: path, required: true, content: {application/json: {schema: {type: object}}}}");
        assertThat(path.parse(GET, Map.of("x", "%7B%7D"))).contains(json("{}"));
        assertThat(path.parse(GET, Map.of())).isEmpty();
        var header = fixture("/p", "{name: X, in: header, content: {text/plain: {schema: {type: string}}}}");
        assertThat(header.parse(GET.withHeader("X", "a").withHeader("X", "b"), Map.of())).contains(json("\"a,b\""));
        var cookie = fixture("/p", "{name: c, in: cookie, content: {application/json: {}}}");
        assertThat(cookie.parse(GET.withHeader("Cookie", "c=%5B1%5D"), Map.of())).contains(json("[1]"));
        var form = fixture("/p", "{name: qs, in: querystring, content: {application/x-www-form-urlencoded: {schema: {type: object, properties: {n: {type: integer}, l: {type: array, items: {type: integer}}}}}}}");
        assertThat(form.parse(HttpRequest.of("GET", "/p?n=1&l=2&l=3&x=a+b"), Map.of())).contains(json("{\"n\":1,\"l\":[2,3],\"x\":\"a b\"}"));
        assertThat(form.parse(GET, Map.of())).isEmpty();
    }

    @Test
    void queryStyles() {
        var exploded = fixture("/p", "{name: obj, in: query, schema: {$ref: '#/components/schemas/Named'}}");
        assertThat(exploded.parse(HttpRequest.of("GET", "/p?a=1&b=2&other=3&a=9"), Map.of())).contains(json("{\"a\":1,\"b\":2}"));
        assertThat(exploded.parse(HttpRequest.of("GET", "/p?other=3"), Map.of())).isEmpty();
        var closed = fixture("/p", "{name: obj, in: query, schema: {type: object, properties: {a: {type: integer}}, additionalProperties: false}}");
        assertThat(closed.parse(HttpRequest.of("GET", "/p?a=1&b=2"), Map.of())).contains(json("{\"a\":1}"));
        var deep = fixture("/p", "{name: f, in: query, style: deepObject, schema: {type: object}}");
        assertThat(deep.parse(HttpRequest.of("GET", "/p?f[a]=1&g[b]=2&f=3&f[]=4"), Map.of())).contains(json("{\"a\":\"1\",\"\":\"4\"}"));
        assertThat(deep.parse(HttpRequest.of("GET", "/p?g[a]=1"), Map.of())).isEmpty();
        var space = fixture("/p", "{name: s, in: query, style: spaceDelimited, schema: {type: array}}");
        assertThat(space.parse(HttpRequest.of("GET", "/p?s=a+b%20c"), Map.of())).contains(json("[\"a\",\"b\",\"c\"]"));
        var pipe = fixture("/p", "{name: s, in: query, style: pipeDelimited, schema: {type: object}}");
        assertThat(pipe.parse(HttpRequest.of("GET", "/p?s=a|1%7cb|2"), Map.of())).contains(json("{\"a\":\"1\",\"b\":\"2\"}"));
        var noSchema = fixture("/p", "{name: s, in: query}");
        assertThat(noSchema.parse(HttpRequest.of("GET", "/p?s=1"), Map.of())).contains(json("\"1\""));
        assertThat(noSchema.parse(HttpRequest.of("GET", "/p?s"), Map.of())).contains(json("\"\""));
    }

    @Test
    void querystringWithoutContentIsAForm() {
        var parameter = new Parameter("qs", ParameterLocation.QUERYSTRING, Optional.empty(), false, false, false,
            ParameterStyle.FORM, true, false, Optional.empty(), Optional.empty(), Map.of(), Map.of(), Map.of());
        var parser = new ParameterParser(new SchemaShapes(api("/p", "[]", "{}").schemas()));
        assertThat(parser.parse(parameter, ParameterParser.Sources.of(HttpRequest.of("GET", "/p?a=1"), Map.of()), Set.of()).toOptional().orElseThrow())
            .contains(json("{\"a\":\"1\"}"));
        assertThat(parser.parse(parameter, ParameterParser.Sources.of(GET, Map.of()), Set.of()).toOptional().orElseThrow()).isEmpty();
    }

    @Test
    void cookieStyles() {
        var object = fixture("/p", "{name: c, in: cookie, schema: {type: object, properties: {a: {type: integer}}}}");
        assertThat(object.parse(GET.withHeader("Cookie", "a=1; b=2"), Map.of())).contains(json("{\"a\":1}"));
        assertThat(object.parse(GET.withHeader("Cookie", "b=2"), Map.of())).isEmpty();
        var raw = fixture("/p", "{name: c, in: cookie, style: cookie, schema: {type: string}}");
        assertThat(raw.parse(GET.withHeader("Cookie", "c=a%20b"), Map.of())).contains(json("\"a%20b\""));
        var form = fixture("/p", "{name: c, in: cookie, schema: {type: string}}");
        assertThat(form.parse(GET.withHeader("Cookie", "c=a%20b"), Map.of())).contains(json("\"a b\""));
        assertThat(form.parse(GET, Map.of())).isEmpty();
        var header = fixture("/p", "{name: H, in: header, schema: {type: object}}");
        assertThat(header.parse(GET.withHeader("H", "a, 1"), Map.of())).contains(json("{\"a\":\"1\"}"));
        assertThat(header.parse(GET, Map.of())).isEmpty();
    }

    @Test
    void schemaShapes() {
        var api = api("/p", "[]", """
            {
              Cyclic: {allOf: [{$ref: '#/components/schemas/Cyclic'}], oneOf: [{type: string}]},
              Untyped: {items: {type: integer}},
              Tuple: {prefixItems: [{type: integer}]},
              Props: {properties: {a: {type: string}}},
              Additional: {additionalProperties: {type: integer}},
              Patterns: {patternProperties: {x: {}}},
              Primitive: {format: date},
              Number: {type: number}
            }""");
        var shapes = new SchemaShapes(api.schemas());
        var schemas = api.model().components().schemas();
        assertThat(shapes.closure(schemas.get("Cyclic"))).hasSize(2);
        assertThat(shapes.shape(schemas.get("Cyclic"))).isEqualTo(SchemaShapes.Shape.PRIMITIVE);
        assertThat(shapes.shape(schemas.get("Untyped"))).isEqualTo(SchemaShapes.Shape.ARRAY);
        assertThat(shapes.shape(schemas.get("Tuple"))).isEqualTo(SchemaShapes.Shape.ARRAY);
        assertThat(shapes.shape(schemas.get("Props"))).isEqualTo(SchemaShapes.Shape.OBJECT);
        assertThat(shapes.shape(schemas.get("Additional"))).isEqualTo(SchemaShapes.Shape.OBJECT);
        assertThat(shapes.shape(schemas.get("Patterns"))).isEqualTo(SchemaShapes.Shape.OBJECT);
        assertThat(shapes.shape(schemas.get("Primitive"))).isEqualTo(SchemaShapes.Shape.PRIMITIVE);
        assertThat(shapes.property(schemas.get("Additional"), "any")).isPresent();
        assertThat(shapes.property(schemas.get("Props"), "other")).isEmpty();
        assertThat(shapes.items(schemas.get("Props"))).isEmpty();
        var number = Optional.of(schemas.get("Number"));
        assertThat(shapes.coerce("1.5e3", number)).isEqualTo(json("1500"));
        assertThat(shapes.coerce("1.", number)).isEqualTo(json("\"1.\""));
        assertThat(shapes.coerce("true", Optional.empty())).isEqualTo(json("\"true\""));
    }

    private static Schema schema(OpenApiDocument api, String name) {
        return api.model().components().schemas().get(name);
    }

    private static MediaType media(Optional<Schema> schema) {
        return new MediaType(schema, Optional.empty(), Optional.empty(), Map.of(), Map.of(), List.of(), Optional.empty(), Map.of());
    }

    @Test
    void bodies() {
        var api = api("/p", "[]", "{Doc: {type: object, properties: {file: {type: string, format: binary}, n: {type: integer}}}}");
        var parser = new BodyParser(new SchemaShapes(api.schemas()));
        var doc = Optional.of(schema(api, "Doc"));
        var multipart = "--b\r\nContent-Disposition: form-data; name=file\r\n\r\nÿ\r\n--b\r\nContent-Disposition: form-data; name=n\r\nContent-Type: text/plain; charset=nope\r\n\r\n5\r\n"
            + "--b\r\nContent-Disposition: form-data; name=j\r\nContent-Type: application/json\r\n\r\n{\r\n--b\r\nContent-Disposition: form-data; name=o\r\nContent-Type: application/octet-stream\r\n\r\nx\r\n--b--";
        var parsed = parser.parse(multipart.getBytes(StandardCharsets.ISO_8859_1), ContentType.parse("multipart/form-data; boundary=b").orElseThrow(),
            media(doc)).toOptional().orElseThrow();
        assertThat(parsed).isEqualTo(json("{\"file\":\"ÿ\",\"n\":5,\"j\":\"{\",\"o\":\"x\"}"));
        var noName = parser.parse("--b\r\nContent-Type: text/plain\r\n\r\nx\r\n--b--".getBytes(StandardCharsets.UTF_8),
            ContentType.parse("multipart/form-data; boundary=b").orElseThrow(), media(Optional.empty())).toOptional().orElseThrow();
        assertThat(noName).isEqualTo(json("{\"\":\"x\"}"));
        var sse = parser.parse("data: a\n\n\n\nid\n\n".getBytes(StandardCharsets.UTF_8), ContentType.parse("text/event-stream").orElseThrow(), media(Optional.empty()));
        assertThat(sse.toOptional().orElseThrow()).isEqualTo(json("[{\"data\":\"a\"},{\"id\":\"\"}]"));
        var binary = parser.parse(new byte[] {(byte) 0xe9}, ContentType.parse("application/pdf").orElseThrow(), media(Optional.empty()));
        assertThat(binary.toOptional().orElseThrow()).isEqualTo(json("\"é\""));
        var latin = parser.parse(new byte[] {(byte) 0xe9}, ContentType.parse("text/plain; charset=ISO-8859-1").orElseThrow(), media(Optional.empty()));
        assertThat(latin.toOptional().orElseThrow()).isEqualTo(json("\"é\""));
        var form = parser.parse("a=1&b=%7B".getBytes(StandardCharsets.UTF_8), ContentType.parse("application/x-www-form-urlencoded").orElseThrow(),
            new MediaType(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of("b", new Encoding(
                Optional.of("application/json"), Map.of(), Optional.empty(), Optional.empty(), false, Map.of(), List.of(), Optional.empty(), Map.of())),
                List.of(), Optional.empty(), Map.of()));
        assertThat(form.toOptional().orElseThrow()).isEqualTo(json("{\"a\":\"1\",\"b\":\"{\"}"));
    }

    @Test
    void xmlBodies() {
        var api = api("/p", "[]", """
            {
              Order: {
                type: object,
                xml: {name: order},
                properties: {
                  id: {type: integer, xml: {nodeType: attribute}},
                  note: {type: string, xml: {nodeType: text}},
                  lines: {type: array, xml: {name: line}, items: {type: integer}},
                  wrappedTags: {type: array, xml: {name: tags, wrapped: true}, items: {type: string, xml: {name: tag}}},
                  missingWrapped: {type: array, xml: {wrapped: true}, items: {type: string}},
                  customer: {type: object, properties: {name: {type: string}}},
                  missing: {type: string, xml: {attribute: true}}
                }
              }
            }""");
        var parser = new BodyParser(new SchemaShapes(api.schemas()));
        var xml = """
            <order id="7">hello<![CDATA[ world]]><line>1</line><line>2</line><tags><tag>a</tag><other>x</other></tags>
            <customer><name>Bob</name></customer><extra>e</extra><nested><deep>d</deep></nested><lines>9</lines></order>""";
        var parsed = parser.parse(xml.getBytes(StandardCharsets.UTF_8), ContentType.parse("application/xml").orElseThrow(),
            media(Optional.of(schema(api, "Order")))).toOptional().orElseThrow();
        assertThat(parsed).isEqualTo(json("""
            {"id":7,"note":"hello world","lines":[1,2],"wrappedTags":["a"],"customer":{"name":"Bob"},"extra":"e","nested":{"deep":"d"}}"""));
        var schemaless = parser.parse("<a><b>1</b></a>".getBytes(StandardCharsets.UTF_8), ContentType.parse("text/xml").orElseThrow(), media(Optional.empty()));
        assertThat(schemaless.toOptional().orElseThrow()).isEqualTo(json("{\"b\":\"1\"}"));
        var doctype = parser.parse("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>".getBytes(StandardCharsets.UTF_8),
            ContentType.parse("application/xml").orElseThrow(), media(Optional.empty()));
        assertThat(doctype.failure().orElseThrow()).startsWith("must be well-formed XML");
        var array = parser.parse("<list><i>1</i><i>2</i></list>".getBytes(StandardCharsets.UTF_8), ContentType.parse("application/xml").orElseThrow(),
            media(Optional.of(schema(api("/p", "[]", "{L: {type: array, items: {type: integer}}}"), "L"))));
        assertThat(array.toOptional().orElseThrow()).isEqualTo(json("[1,2]"));
    }
}
