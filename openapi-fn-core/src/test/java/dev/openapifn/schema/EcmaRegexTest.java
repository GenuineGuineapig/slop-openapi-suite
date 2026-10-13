package dev.openapifn.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EcmaRegexTest {

    private static boolean find(String regex, String input) {
        return EcmaRegex.compile(regex).orElseThrow(AssertionError::new).matcher(input).find();
    }

    static Stream<Arguments> translations() {
        return Stream.of(
            Arguments.of("^\\d+$", "123", true),
            Arguments.of("^\\d$", "\u0663", false),
            Arguments.of("^[\\d]$", "5", true),
            Arguments.of("^[\\d]$", "a", false),
            Arguments.of("^\\D$", "a", true),
            Arguments.of("^[\\D]$", "5", false),
            Arguments.of("^[\\D]$", "a", true),
            Arguments.of("^\\w$", "_", true),
            Arguments.of("^\\w$", "\u00e9", false),
            Arguments.of("^[\\w]$", "z", true),
            Arguments.of("^\\W$", "-", true),
            Arguments.of("^[\\W]$", "a", false),
            Arguments.of("^[\\W]$", "-", true),
            Arguments.of("^\\s$", "\u00a0", true),
            Arguments.of("^[\\s]$", "\u2003", true),
            Arguments.of("^\\S$", "a", true),
            Arguments.of("^[\\S]$", "a", true),
            Arguments.of("^\\S$", "\u2003", false),
            Arguments.of("^\\p{Letter}$", "\u00e9", true),
            Arguments.of("^\\p{L}$", "1", false),
            Arguments.of("^\\P{Lu}$", "a", true),
            Arguments.of("^\\p{Lowercase_Letter}$", "a", true),
            Arguments.of("^\\p{Uppercase_Letter}$", "A", true),
            Arguments.of("^\\p{Number}$", "7", true),
            Arguments.of("^\\p{Decimal_Number}$", "7", true),
            Arguments.of("^\\p{digit}$", "7", true),
            Arguments.of("^\\p{Punctuation}$", "!", true),
            Arguments.of("^\\p{punct}$", "!", true),
            Arguments.of("^\\p{Symbol}$", "+", true),
            Arguments.of("^\\p{Separator}$", " ", true),
            Arguments.of("^\\p{Mark}$", "\u0301", true),
            Arguments.of("^\\p{Other}$", "\u0000", true),
            Arguments.of("^\\p{Script=Greek}$", "\u03b1", true),
            Arguments.of("^\\p{sc=Greek}$", "a", false),
            Arguments.of("^\\p{scx=Greek}$", "\u03b1", true),
            Arguments.of("^\\p{Script_Extensions=Greek}$", "\u03b1", true),
            Arguments.of("^\\p{General_Category=Lu}$", "A", true),
            Arguments.of("^\\p{gc=Letter}$", "x", true),
            Arguments.of("^\\pL$", "x", true),
            Arguments.of("^\\v$", "\u000b", true),
            Arguments.of("^\\v$", "\n", false),
            Arguments.of("^a\\0$", "a\u0000", true),
            Arguments.of("^[^]$", "\n", true),
            Arguments.of("^a[]$", "a", false),
            Arguments.of("^a$", "a\n", false),
            Arguments.of("^[$]$", "$", true),
            Arguments.of("^\\$$", "$", true),
            Arguments.of("^\\b\\x41\\u0042\\cJ$", "AB\n", true),
            Arguments.of("(?<n>a)\\k<n>", "aa", true),
            Arguments.of("(?:a)", "a", true),
            Arguments.of("(?i:A)", "a", true),
            Arguments.of("a\\0", "a\u0000", true));
    }

    @ParameterizedTest
    @MethodSource("translations")
    void translatesEcmaSemantics(String regex, String input, boolean expected) {
        assertThat(find(regex, input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\a", "\\e", "(?i)abc", "(?ims)a", "(", "\\p{Foo=Bar}", "\\p", "\\p{L"})
    void rejectsInvalidExpressions(String regex) {
        assertThat(EcmaRegex.compile(regex).failure().orElseThrow()).startsWith("Invalid regular expression");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\01"})
    void keepsOctalLikeEscapes(String regex) {
        assertThat(EcmaRegex.compile(regex).isOk()).isTrue();
        assertThat(EcmaRegex.compile(regex)).isSameAs(EcmaRegex.compile(regex));
    }
}
