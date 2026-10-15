package dev.openapifn.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.DocumentParser;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.Parameter;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The normative examples of the OpenAPI Specification as test vectors, read from the specification
 * texts (see {@code oas-spec/README.md}): the Style Examples table of 3.0.4, 3.1.1, 3.1.2 and
 * 3.2.0, and the Parameter Object examples of 3.2.0 with their {@code dataValue} and
 * {@code serializedValue}, for every location, style and media type they show. Each value must
 * serialize as the specification shows, and a request carrying the serialized form must validate
 * and parse to the value.
 */
class SpecificationExamplesTest {

    private static final Path SPECS = specs();
    private static final Pattern SPAN = Pattern.compile("</?span[^>]*>");
    private static final JsonValue STRING = JsonString.of("blue");
    private static final JsonValue ARRAY = new JsonArray(List.of(JsonString.of("blue"), JsonString.of("black"), JsonString.of("brown")));
    private static final JsonValue OBJECT = JsonObject.builder().put("R", JsonNumber.of(100)).put("G", JsonNumber.of(200)).put("B", JsonNumber.of(150)).build();

    private static Path specs() {
        try {
            return Path.of(SpecificationExamplesTest.class.getResource("/oas-spec").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> lines(String version) {
        try {
            return Files.readAllLines(SPECS.resolve(version + ".md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The Style Examples table

    /** One cell of the table: how a value of {@code type} is serialized with {@code style} and {@code explode}. */
    private record Cell(String version, String style, boolean explode, String type, String serialized) {
        @Override
        public String toString() {
            return version + " " + style + (explode ? " explode " : " ") + type;
        }
    }

    private static List<Cell> styleTable(String version) {
        var lines = lines(version);
        var start = lines.indexOf(lines.stream().filter(line -> line.matches("#+ Style Examples")).findFirst().orElseThrow());
        var cells = new ArrayList<Cell>();
        var types = List.of("undefined", "string", "array", "object");
        var rows = lines.subList(start, lines.size()).stream().dropWhile(line -> !line.startsWith("| [`style`]")).skip(2)
            .takeWhile(line -> line.startsWith("|")).toList();
        for (var row : rows) {
            var columns = Stream.of(row.substring(1, row.length() - 1).split("\\|")).map(cell -> SPAN.matcher(cell).replaceAll("").strip()).toList();
            for (int i = 0; i < types.size(); i++) {
                var text = columns.get(i + 2);
                if (!text.equals("_n/a_")) {
                    // 3.0.4 shows the query string with its leading "?".
                    var serialized = text.equals("_empty_") ? "" : text.replaceFirst("^\\?", "");
                    cells.add(new Cell(version, columns.get(0), !columns.get(1).equals("false"), types.get(i), serialized));
                }
            }
        }
        return cells;
    }

    private static ParameterLocation location(String style) {
        return switch (style) {
            case "matrix", "label", "simple" -> ParameterLocation.PATH;
            case "cookie" -> ParameterLocation.COOKIE;
            default -> ParameterLocation.QUERY;
        };
    }

    private static JsonValue value(String type) {
        return switch (type) {
            case "string" -> STRING;
            case "array" -> ARRAY;
            case "object" -> OBJECT;
            default -> JsonString.of(""); // the undefined (formerly empty) column, shown for the empty string
        };
    }

    private static JsonObject schema(String type) {
        return switch (type) {
            case "array" -> JsonObject.builder().put("type", "array").put("items", JsonObject.builder().put("type", "string").build()).build();
            case "object" -> {
                var integer = JsonObject.builder().put("type", "integer").build();
                yield JsonObject.builder().put("type", "object").put("properties", JsonObject.builder().put("R", integer).put("G", integer).put("B", integer)
                    .build()).build();
            }
            default -> JsonObject.builder().put("type", "string").build();
        };
    }

    @TestFactory
    Stream<DynamicTest> theStyleExamplesTables() {
        var cells = Stream.of("3.0.4", "3.1.1", "3.1.2", "3.2.0").flatMap(version -> styleTable(version).stream()).toList();
        assertThat(cells).hasSizeGreaterThan(150);
        return cells.stream().map(cell -> DynamicTest.dynamicTest(cell.toString(), () -> {
            var in = location(cell.style());
            var parameter = JsonObject.builder().put("name", "color").put("in", in.value()).put("style", cell.style())
                .put("explode", cell.explode()).put("required", in == ParameterLocation.PATH).put("schema", schema(cell.type())).build();
            check(parameter, Optional.of(value(cell.type())), cell.serialized());
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // The Parameter Object examples

    /** An example of a parameter: its data, if given, and its serialization. */
    private record Example(String name, JsonObject parameter, Optional<JsonValue> data, String serialized) {
        @Override
        public String toString() {
            return parameter.string("in").orElseThrow() + " " + parameter.string("name").orElse("") + ": " + name;
        }
    }

    private static List<Example> parameterExamples() {
        var lines = lines("3.2.0");
        var section = lines.subList(lines.indexOf("#### Parameter Object Examples"), lines.indexOf("### Request Body Object"));
        var examples = new ArrayList<Example>();
        for (int i = 0; i < section.size(); i++) {
            if (!section.get(i).equals("```yaml")) {
                continue;
            }
            var block = new StringBuilder();
            for (i++; !section.get(i).equals("```"); i++) {
                block.append(section.get(i)).append('\n');
            }
            var parameter = erratum((JsonObject) DocumentParser.parse(URI.create("mem:/parameter.yaml"), block.toString())
                .orElseThrow(d -> new AssertionError(d.toString())).root());
            var declared = parameter.object("examples").orElseThrow();
            var named = declared.has("dataValue") || declared.has("serializedValue") ? Map.<String, JsonValue>of("example", declared) : declared.members();
            named.forEach((name, example) -> {
                var object = (JsonObject) example;
                var serialized = object.string("serializedValue").or(() -> object.string("serializeValue")).orElseThrow();
                examples.add(new Example(name, parameter.without("examples"), object.get("dataValue"), serialized));
            });
        }
        return examples;
    }

    /**
     * Repairs the errata of the 3.2.0 examples, which its own JSON Schema rejects too: one puts
     * {@code dataValue} and {@code serializedValue} right into a Media Type Object's
     * {@code examples} (rather than into a named Example Object), one leaves out the required
     * {@code name}. (Two put them right into the Parameter Object's {@code examples}, and one
     * misspells {@code serializedValue}; the reading tolerates those.)
     */
    private static JsonObject erratum(JsonObject parameter) {
        var repaired = parameter.has("name") ? parameter : parameter.with("name", JsonString.of("query"));
        var content = repaired.object("content").map(media -> {
            var fixed = JsonObject.builder();
            media.members().forEach((type, mediaType) -> {
                var object = (JsonObject) mediaType;
                var examples = object.object("examples").filter(e -> e.has("dataValue"));
                fixed.put(type, examples.<JsonValue>map(e -> object.with("examples", JsonObject.builder().put("example", e).build())).orElse(object));
            });
            return fixed.build();
        });
        return content.map(media -> repaired.with("content", media)).orElse(repaired);
    }

    @TestFactory
    Stream<DynamicTest> theParameterObjectExamples() {
        var examples = parameterExamples();
        assertThat(examples).hasSize(12);
        return examples.stream().map(example -> DynamicTest.dynamicTest(example.toString(), () -> check(example.parameter(), example.data(), example.serialized())));
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * {@code data} serializes to {@code serialized} (for parameters described by a schema and a
     * style), and a request carrying {@code serialized} is valid and parses to {@code data}.
     */
    private static void check(JsonObject parameterJson, Optional<JsonValue> data, String serialized) {
        var in = ParameterLocation.of(parameterJson.string("in").orElseThrow()).orElseThrow();
        var name = parameterJson.string("name").orElseThrow();
        var path = in == ParameterLocation.PATH ? "/items/{" + name + "}" : "/items";
        var description = JsonObject.builder().put("openapi", "3.2.0").put("info", JsonObject.builder().put("title", "t").put("version", "v").build())
            .put("paths", JsonObject.builder().put(path, JsonObject.builder().put("get", JsonObject.builder()
                .put("parameters", new JsonArray(List.of(parameterJson)))
                .put("responses", JsonObject.builder().put("204", JsonObject.builder().put("description", "ok").build()).build()).build()).build()).build())
            .build();
        OpenApiDocument api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.json"), JsonWriter.compact(description))
            .orElseThrow(d -> new AssertionError(d.toString()));
        var parameter = api.operations().getFirst().parameters().getFirst();
        data.ifPresent(value -> assertThat(serialize(parameter, value)).as("serialized").isEqualTo(serialized));
        var request = switch (in) {
            case PATH -> HttpRequest.of("GET", "/items/" + serialized);
            case QUERY, QUERYSTRING -> HttpRequest.of("GET", "/items?" + serialized);
            case HEADER -> HttpRequest.of("GET", "/items").withHeader(name, serialized);
            case COOKIE -> HttpRequest.of("GET", "/items").withHeader("Cookie", serialized);
        };
        var validation = OpenApiValidator.of(api).validateRequest(request);
        assertThat(validation.report().issues()).as("findings").isEmpty();
        data.ifPresent(value -> assertThat(validation.parameters().get(in.value() + "." + name)).as("parsed").satisfies(parsed -> assertThat(equal(parsed, value))
            .as("%s parses to %s", serialized, value.toJson()).isTrue()));
    }

    private static String serialize(Parameter parameter, JsonValue value) {
        var spec = ParameterSerializer.Spec.of(parameter);
        return switch (parameter.in()) {
            case PATH -> ParameterSerializer.path(spec, value);
            case HEADER -> ParameterSerializer.header(spec, value);
            case COOKIE -> ParameterSerializer.cookieHeader(ParameterSerializer.cookie(spec, value));
            case QUERYSTRING -> ParameterSerializer.querystring(spec, value);
            default -> ParameterSerializer.queryString(ParameterSerializer.query(spec, value));
        };
    }

    /** JSON equality, numbers by value. */
    private static boolean equal(JsonValue actual, JsonValue expected) {
        return switch (expected) {
            case JsonNumber number -> actual instanceof JsonNumber other && other.value().compareTo(number.value()) == 0;
            case JsonArray array -> actual instanceof JsonArray other && other.size() == array.size()
                && java.util.stream.IntStream.range(0, array.size()).allMatch(i -> equal(other.values().get(i), array.values().get(i)));
            case JsonObject object -> actual instanceof JsonObject other && other.members().keySet().equals(object.members().keySet())
                && object.members().entrySet().stream().allMatch(entry -> equal(other.get(entry.getKey()).orElseThrow(), entry.getValue()));
            default -> actual.equals(expected);
        };
    }

    /**
     * The {@code application/x-www-form-urlencoded} request body examples of 3.2.0 ("URL Encoded
     * Form with JSON Values" and "with Binary Values"): the data serializes to the body shown, which
     * a server validates and parses back to the data.
     */
    @org.junit.jupiter.api.Test
    void theFormBodyExamples() {
        var text = String.join("\n", lines("3.2.0"));
        var withJson = "id=f81d4fae-7dec-11d0-a765-00a0c91e6bf6&address=%7B%22streetAddress%22%3A%22123+Example+Dr.%22%2C%22city%22%3A%22Somewhere%22%2C"
            + "%22state%22%3A%22CA%22%2C%22zip%22%3A%2299999%2B1234%22%7D";
        var icon = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAABGdBTUEAALGPC_xhBQAAADhlWElmTU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAAqACAAQAAAABAAAAAqADAAQAAAABAAAAAgAAAADO0J6QAAAAEElEQVQIHWP8zwACTGCSAQANHQEDqtPptQAAAABJRU5ErkJggg";
        var withBinary = "name=example&icon=" + icon + "%3D%3D";
        assertThat(text).contains(withJson, withBinary);
        var jsonData = dev.openapifn.json.Json.parse("""
            {"id": "f81d4fae-7dec-11d0-a765-00a0c91e6bf6",
             "address": {"streetAddress": "123 Example Dr.", "city": "Somewhere", "state": "CA", "zip": "99999+1234"}}
            """).orElseThrow(e -> new AssertionError(e.toString()));
        var binaryData = JsonObject.builder().put("name", "example").put("icon", icon + "==").build();
        assertThat(dev.openapifn.http.BodyWriter.form(jsonData)).isEqualTo(withJson);
        assertThat(dev.openapifn.http.BodyWriter.form(binaryData)).isEqualTo(withBinary);
        var api = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/forms.yaml"), """
            openapi: 3.2.0
            info: {title: t, version: v}
            paths:
              /addresses:
                post:
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            id: {type: string, format: uuid}
                            address: {type: object, properties: {}}
                  responses: {'204': {description: ok}}
              /icons:
                post:
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            name: {type: string}
                            icon: {type: string, contentEncoding: base64url}
                        encoding:
                          icon: {contentType: 'image/png, image/jpeg'}
                  responses: {'204': {description: ok}}
            """).orElseThrow(d -> new AssertionError(d.toString()));
        var validator = OpenApiValidator.of(api);
        var addresses = validator.validateRequest(HttpRequest.of("POST", "/addresses").withBody("application/x-www-form-urlencoded", withJson));
        assertThat(addresses.report().issues()).isEmpty();
        assertThat(equal(addresses.body().orElseThrow(), jsonData)).as("parsed %s", addresses.body()).isTrue();
        var icons = validator.validateRequest(HttpRequest.of("POST", "/icons").withBody("application/x-www-form-urlencoded", withBinary));
        assertThat(icons.report().issues()).isEmpty();
        assertThat(equal(icons.body().orElseThrow(), binaryData)).as("parsed %s", icons.body()).isTrue();
    }
}
