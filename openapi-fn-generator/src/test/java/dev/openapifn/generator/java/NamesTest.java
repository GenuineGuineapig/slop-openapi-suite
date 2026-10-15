package dev.openapifn.generator.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void splitsWordsAtSeparatorsAndCaseChanges() {
        assertThat(Names.words("HTTPServer")).containsExactly("HTTP", "Server");
        assertThat(Names.words("getHTTPResponse")).containsExactly("get", "HTTP", "Response");
        assertThat(Names.words("ABC")).containsExactly("ABC");
        assertThat(Names.words("v2Api")).containsExactly("v2Api");
        assertThat(Names.words("pet_store-v2 id")).containsExactly("pet", "store", "v2", "id");
        assertThat(Names.words("--")).isEmpty();
    }

    @Test
    void casesNames() {
        assertThat(Names.pascal("pet_store")).isEqualTo("PetStore");
        assertThat(Names.pascal("--")).isEqualTo("Unnamed");
        assertThat(Names.pascal("1st place")).isEqualTo("_1stPlace");
        assertThat(Names.camel("X-Request-Id")).isEqualTo("xRequestId");
        assertThat(Names.camel("")).isEqualTo("unnamed");
        assertThat(Names.camel("class")).isEqualTo("class_");
        assertThat(Names.constant("someValue")).isEqualTo("SOME_VALUE");
        assertThat(Names.constant("")).isEqualTo("EMPTY");
        assertThat(Names.constant("1a")).isEqualTo("_1A");
    }

    @Test
    void makesNamesUnique() {
        var taken = new HashSet<>(Set.of("a", "a2"));
        assertThat(Names.unique("a", taken)).isEqualTo("a3");
        assertThat(Names.unique("b", taken)).isEqualTo("b");
        assertThat(taken).contains("a3", "b");
    }

    @Test
    void quotesStringLiterals() {
        assertThat(Names.literal("q\"b\\\n\r\t\u0001\u0085 é")).isEqualTo("\"q\\\"b\\\\\\n\\r\\t\\u0001\\u0085 é\"");
    }

    @Test
    void neutralizesJavadoc() {
        assertThat(Names.javadoc(" a */ b\n  @tag x@y ")).isEqualTo("a *&#47; b\n  &#64;tag x@y");
    }
}
