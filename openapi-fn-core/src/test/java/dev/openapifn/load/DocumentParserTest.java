package dev.openapifn.load;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.Json;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import java.net.URI;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private static final URI URI_ = URI.create("mem:/doc.yaml");

    private static Document parse(String yaml) {
        return DocumentParser.parse(URI_, yaml).orElseThrow(d -> new AssertionError(d.toString()));
    }

    private static Diagnostic fail(String yaml) {
        return DocumentParser.parse(URI_, yaml).failure().orElseThrow();
    }

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    @Test
    void convertsScalarsWithCoreSchema() {
        var doc = parse("""
            a: ~
            b: null
            c: True
            d: false
            e: 42
            f: -7
            g: 0x1F
            h: 0o17
            i: 1.5
            j: -.5e2
            k: .inf
            l: -.Inf
            m: .NaN
            n: "42"
            o: !!str 7
            p: 2024-01-01
            q: +12
            r: +1.5
            200: ok
            """);
        assertThat(doc.root()).isEqualTo(json("""
            {"a":null,"b":null,"c":true,"d":false,"e":42,"f":-7,"g":31,"h":15,"i":1.5,"j":-50,"k":".inf","l":"-.Inf","m":".NaN",
             "n":"42","o":"7","p":"2024-01-01","q":12,"r":1.5,"200":"ok"}"""));
    }

    @Test
    void parsesJsonAndSequences() {
        assertThat(parse("{\"a\": [1, {\"b\": \"c\"}]}").root()).isEqualTo(json("{\"a\":[1,{\"b\":\"c\"}]}"));
        assertThat(parse("- a\n- b\n").root()).isEqualTo(json("[\"a\",\"b\"]"));
    }

    @Test
    void emptyDocumentIsNull() {
        assertThat(parse("").root()).isEqualTo(JsonNull.INSTANCE);
        assertThat(parse("# only a comment").root()).isEqualTo(JsonNull.INSTANCE);
    }

    @Test
    void recordsSourcePositions() {
        var doc = parse("a:\n  b:\n    - x\n    - y\n");
        assertThat(doc.position(JsonPointer.of("a", "b", "1"))).contains(new Position(4, 7));
        assertThat(doc.position(JsonPointer.of("a"))).contains(new Position(2, 3));
        assertThat(doc.position(JsonPointer.of("a", "b", "1", "missing"))).contains(new Position(4, 7));
        assertThat(doc.resolve(JsonPointer.of("a", "b", "0"))).contains(json("\"x\""));
    }

    @Test
    void honoursMergeKeys() {
        var doc = parse("""
            base: &base {a: 1, b: 2}
            other: &other {c: 3}
            single:
              <<: *base
              b: 20
            multiple:
              <<: [*base, *other]
              a: 10
            quoted:
              "<<": literal
            """);
        assertThat(doc.root()).isEqualTo(json("""
            {"base":{"a":1,"b":2},"other":{"c":3},"single":{"b":20,"a":1},"multiple":{"a":10,"b":2,"c":3},"quoted":{"<<":"literal"}}"""));
    }

    @Test
    void reusesNonRecursiveAliases() {
        assertThat(parse("a: &x [1]\nb: *x\n").root()).isEqualTo(json("{\"a\":[1],\"b\":[1]}"));
    }

    @Test
    void rejectsInvalidMergeSources() {
        assertThat(fail("a:\n  <<: 1\n").message()).startsWith("Invalid YAML");
        assertThat(fail("a:\n  <<: [1]\n").message()).startsWith("Invalid YAML");
    }

    @Test
    void rejectsDuplicateKeys() {
        var diagnostic = fail("a: 1\nb: 2\na: 3\n");
        assertThat(diagnostic.message()).isEqualTo("Duplicate mapping key 'a'");
        assertThat(diagnostic.pointer()).isEqualTo(JsonPointer.of("a"));
        assertThat(diagnostic.position()).contains(new Position(3, 1));
    }

    @Test
    void rejectsComplexKeys() {
        assertThat(fail("? [a, b]\n: c\n").message()).startsWith("Invalid YAML");
    }

    @Test
    void rejectsRecursiveAliases() {
        assertThat(fail("a: &x\n  b: *x\n").message()).isEqualTo("Recursive YAML alias");
    }

    @Test
    void reportsSyntaxErrorsWithPosition() {
        var diagnostic = fail("a: [1, 2\nb: 3\n");
        assertThat(diagnostic.message()).startsWith("Invalid YAML:");
        assertThat(diagnostic.position()).isPresent();
        assertThat(diagnostic.isError()).isTrue();
    }

    @Test
    void reportsUnmarkedEngineErrors() {
        var diagnostic = fail("a: *undefined\n");
        assertThat(diagnostic.message()).startsWith("Invalid YAML:");
    }
}
