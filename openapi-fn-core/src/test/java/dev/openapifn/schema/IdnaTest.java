package dev.openapifn.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdnaTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "''|false", "a|true", "a.|false", ".a|false", "a..b|false",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|true",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|false",
        "xn--mnchen-3ya|true", "XN--MNCHEN-3YA|true", "xn--abc|false", "xn--a-|false",
        "xn--99999999999999999|false", "xn---|false",
    })
    void asciiHostnames(String host, boolean valid) {
        assertThat(Idna.isValid(host, false)).isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "münchen.de|true", "MÜNCHEN.de|true", "a­b|true", "l·l|true", "a·l|false", "l·|false", "·l|false",
        "α͵β|true", "α͵|false", "α͵a|false", "א׳|true", "a׳|false", "׳|false", "ア・カ|true", "a・b|false",
        "٠١ب|false", "ب٠١|true", "ب۰١|false", "ب۰۱|true", "क्‍ष|true", "क‍ष|false", "‍|false",
        "क्‌ष|true", "بي‌بي|true", "ب‌ا|true", "ا‌ب|false",
        "ب‌|false", "a‌b|false", "́a|false", "a-|false", "-a|false", "ab--c|false", "aـb|false", "aᄀb|false",
        "aßb|true", "a!b|false", "a͸b|false",
        "ب.a|true", "ب1|true", "بa|false", "1ب|false", "ب.1a|false", "a.بب|true", "ب٠1|false", "ب́|true",
        "á.ب|true", "a!.ب|false", "aب.ب|false", "a۰b|true",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaä|false",
        "-ä|false", "ä-|false", "ää--ä|false", "äb-c|true", "\u200c|false", "\u0628\u064B\u200c\u064B\u0628|true",
        "\u0628\u0661\u200c\u0628|false", "\u06FF\u0661|true", "\u0628\u02B9|false", "\u0628\u0031\u0661|false",
    })
    void unicodeHostnames(String host, boolean valid) {
        assertThat(Idna.isValid(host, true)).as(host).isEqualTo(valid);
    }

    @Test
    void decodedALabelsAreNotMapped() {
        assertThat(Idna.isValid("xn--" + Idna.encode("a\u1D2Cb").orElseThrow(), false)).isFalse();
        assertThat(Idna.isValid("xn--" + Idna.encode("abc").orElseThrow(), false)).isFalse();
        assertThat(Idna.isValid("xn--" + Idna.encode("a\u0301").orElseThrow(), false)).isFalse();
    }

    @Test
    void totalLengthIsLimited() {
        var label = "a".repeat(63);
        assertThat(Idna.isValid(String.join(".", label, label, label, "a".repeat(61)), false)).isTrue();
        assertThat(Idna.isValid(String.join(".", label, label, label, "a".repeat(62)), false)).isFalse();
    }

    @Test
    void punycodeRoundTrips() {
        assertThat(Idna.encode("münchen")).contains("mnchen-3ya");
        assertThat(Idna.decode("mnchen-3ya")).contains("münchen");
        assertThat(Idna.encode("ü")).contains("tda");
        assertThat(Idna.decode("tda")).contains("ü");
        assertThat(Idna.decode("-tda")).contains("ü");
        assertThat(Idna.decode("A")).isEmpty();
        assertThat(Idna.decode("9")).isEmpty();
        assertThat(Idna.decode("99999999999")).isEmpty();
        assertThat(Idna.decode("zzzzzzzzzzzzzzz9")).isEmpty();
        assertThat(Idna.decode("999999999a")).isEmpty();
        assertThat(Idna.decode("99999a")).isEmpty();
        assertThat(Idna.decode("99999999")).isEmpty();
        var many = "一丁丂七丄丅丆万丈三上下丌不与丏";
        assertThat(Idna.decode(Idna.encode(many).orElseThrow())).contains(many);
    }
}
