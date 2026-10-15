package dev.openapifn.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonPointerTest {

    private static JsonPointer pointer(String text) {
        return JsonPointer.parse(text).orElseThrow(IllegalArgumentException::new);
    }

    @Test
    void parsesAndPrints() {
        assertThat(pointer("")).isEqualTo(JsonPointer.ROOT);
        assertThat(pointer("/paths/~1pets~1{id}/get/a~0b").tokens()).containsExactly("paths", "/pets/{id}", "get", "a~b");
        assertThat(pointer("/paths/~1pets~1{id}/get/a~0b")).hasToString("/paths/~1pets~1{id}/get/a~0b");
        assertThat(pointer("/")).isEqualTo(JsonPointer.of(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "/a~2", "/a~"})
    void rejectsInvalidPointers(String text) {
        assertThat(JsonPointer.parse(text).isErr()).isTrue();
    }

    @Test
    void navigation() {
        var p = JsonPointer.of("a").child("b").child(2);
        assertThat(p).hasToString("/a/b/2");
        assertThat(p.parent()).contains(JsonPointer.of("a", "b"));
        assertThat(JsonPointer.ROOT.parent()).isEmpty();
        assertThat(p.last()).contains("2");
        assertThat(JsonPointer.ROOT.last()).isEmpty();
        assertThat(p.isRoot()).isFalse();
        assertThat(JsonPointer.ROOT.isRoot()).isTrue();
        assertThat(p.startsWith(JsonPointer.of("a"))).isTrue();
        assertThat(p.startsWith(JsonPointer.of("b"))).isFalse();
        assertThat(JsonPointer.of("a").startsWith(p)).isFalse();
        assertThat(JsonPointer.of("a").append(JsonPointer.of("b", "c"))).isEqualTo(JsonPointer.of("a", "b", "c"));
    }

    @Test
    void resolves() {
        var doc = Json.parse("{\"a\":[{\"b\":1},2],\"\":3}").orElseThrow(e -> new AssertionError(e));
        assertThat(pointer("/a/0/b").resolve(doc)).contains(JsonNumber.of(1));
        assertThat(pointer("/a/1").resolve(doc)).contains(JsonNumber.of(2));
        assertThat(pointer("/").resolve(doc)).contains(JsonNumber.of(3));
        assertThat(pointer("").resolve(doc)).contains(doc);
        assertThat(pointer("/a/01").resolve(doc)).isEmpty();
        assertThat(pointer("/a/-").resolve(doc)).isEmpty();
        assertThat(pointer("/a/").resolve(doc)).isEmpty();
        assertThat(pointer("/a/1x").resolve(doc)).isEmpty();
        assertThat(pointer("/a/9999999999").resolve(doc)).isEmpty();
        assertThat(pointer("/a/5").resolve(doc)).isEmpty();
        assertThat(pointer("/a/1/c").resolve(doc)).isEmpty();
        assertThat(pointer("/missing").resolve(doc)).isEmpty();
    }

    @Test
    void uriFragments() {
        assertThat(JsonPointer.of("paths", "/pets/{id}", "é").toUriFragment()).isEqualTo("#/paths/~1pets~1%7Bid%7D/%C3%A9");
    }
}
