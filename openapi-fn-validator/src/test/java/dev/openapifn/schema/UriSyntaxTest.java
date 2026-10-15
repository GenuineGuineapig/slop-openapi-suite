package dev.openapifn.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UriSyntaxTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "https://user:pw@example.com:8080/a/b?c=d&e#f|true|false|true", "urn:isbn:0451450523|true|false|true", "1http://x|true|false|false",
        "//example.com|false|false|true", "//|false|false|true", "/a:b|false|false|true", "a:b|false|false|true", "a/b:c|false|false|true",
        "./a:b|false|false|true", ":a|false|false|false", "a b|false|false|false", "http://[::1]:80/|true|false|true",
        "http://[v1.a:b]/|true|false|true", "http://[::1|true|false|false", "http://[::1]x|true|false|false", "http://[zz]/|true|false|false",
        "http://a@b@c/|true|false|false", "http://x:8a/|true|false|false", "http://x/%41|true|false|true", "http://x/%4|true|false|false",
        "http://x/%G1|true|false|false", "http://x/%1G|true|false|false", "http://x?%zz|true|false|false", "http://x#a#b|true|false|false",
        "http://ä.de|true|true|true", "http://x/?|true|true|true", "http://x/|true|true|false", "http://x/𐀀|true|true|true",
        "http://x/\uDB80\uDC00|true|true|false", "http://x/󯿾|true|true|false", "http://x/﷐|true|true|false",
        "http://x/󠀀|true|true|false", "http://x/\uDB44\uDC00|true|true|true", "http://x/􀀀|true|true|false",
        "http://x/?󰀀|true|true|true", "http://x/?􀀀|true|true|true", "http://x/?􏿾|true|true|false",
        "http://ü:1@x|true|true|true", "http://x/a\u007fb|true|true|false",
    })
    void validates(String value, boolean absolute, boolean international, boolean valid) {
        assertThat(UriSyntax.isValid(value, absolute, international)).as(value).isEqualTo(valid);
    }
}
