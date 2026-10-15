package dev.openapifn.schema;

import static dev.openapifn.schema.Schemas.json;
import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.json.JsonString;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FormatsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "date|2024-02-29|true", "date|2023-02-29|false", "date|1900-02-29|false", "date|2000-02-29|true", "date|2024-13-01|false",
        "date|2024-00-01|false", "date|2024-01-00|false", "date|2024-1-01|false",
        "time|23:59:60Z|true", "time|23:59:60+00:00|true", "time|15:59:60-08:00|true", "time|22:59:60Z|false", "time|24:00:00Z|false",
        "time|12:60:00Z|false", "time|12:00:61Z|false", "time|12:00:00+24:00|false", "time|12:00:00+00:60|false", "time|12:00:00.123z|true",
        "time|12:00:00|false",
        "date-time|2024-01-01T12:00:00Z|true", "date-time|2024-01-01t12:00:00z|true", "date-time|2024-01-01 12:00:00Z|false",
        "date-time|T12:00:00Z|false", "date-time|2024-01-01T25:00:00Z|false",
        "duration|P1Y2M3DT4H5M6S|true", "duration|P4W|true", "duration|PT1H|true", "duration|P1W1D|false", "duration|PT|false",
        "email|a.b+c@example.com|true", "email|\"a b\"@example.com|true", "email|@example.com|false", "email|a..b@example.com|false",
        "email|a@[127.0.0.1]|true", "email|a@[IPv6:::1]|true", "email|a@[300.0.0.1]|false", "email|a@-x.com|false", "email|ä@example.com|false",
        "idn-email|ä@exämple.com|true", "idn-email|ä@|false",
        "hostname|example.com|true", "hostname|-x.com|false", "hostname|xn--nxasmq6b.com|true", "hostname|ab--cd.com|false",
        "hostname|bücher.de|false", "idn-hostname|bücher.de|true",
        "ipv4|192.168.0.1|true", "ipv4|192.168.0.01|false", "ipv4|256.0.0.1|false",
        "ipv6|::1|true", "ipv6|::|true", "ipv6|1:2:3:4:5:6:7:8|true", "ipv6|1:2:3:4:5:6:7|false", "ipv6|1:2:3:4:5:6:7:8:9|false",
        "ipv6|1::2::3|false", "ipv6|::ffff:192.168.0.1|true", "ipv6|::ffff:192.168.0.01|false", "ipv6|12345::|false", "ipv6|1:2:3:4:5:6:7::|true",
        "ipv6|:1::|false", "ipv6|1::2:|false", "ipv6|ä::1|false", "ipv6|''|false", "ipv6|1.2.3.4|false", "ipv6|1:2:3:4:5:6:1.2.3.4|true",
        "uri|https://example.com/a?b#c|true", "uri|relative/path|false", "uri-reference|relative/path|true", "uri-reference|a:b|true",
        "iri|https://bücher.de/ä|true", "uri|https://bücher.de|false", "iri-reference|//bücher.de|true",
        "uri-template|/users/{id}{?q,limit:3}{&x*}|true", "uri-template|/a/{|false", "uri-template|/a}|false", "uri-template|{%zz}|false",
        "uri-template|{a b}|false", "uri-template|a%2F|true", "uri-template|a%G0|false", "uri-template|a<b|false", "uri-template|a%0G|false", "uri-template|'a b'|false", "uri-template|a%2|false", "uri-template|ä|true", "uri-template|a\u007fb|false",
        "json-pointer|/a/b~0|true", "json-pointer|a|false", "relative-json-pointer|1/a|true", "relative-json-pointer|0#|true",
        "relative-json-pointer|-1|false", "regex|^a+$|true", "regex|(|false",
        "uuid|123e4567-e89b-12d3-a456-426614174000|true", "uuid|123e4567e89b12d3a456426614174000|false",
        "byte|aGVsbG8=|true", "byte|aGVsbG8|false", "byte|!!!!|false",
    })
    void validatesStringFormats(String format, String value, boolean valid) {
        assertThat(Formats.check(format, JsonString.of(value)).isEmpty()).as(format + " " + value).isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "int32|2147483647|true", "int32|2147483648|false", "int32|-2147483649|false", "int32|1.5|false", "int64|9223372036854775808|false",
        "int64|-9223372036854775808|true", "float|3.5e38|false", "float|1.5|true", "double|1e309|false", "double|-1e308|true",
        "date|1|true", "unknown|1|true", "password|\"x\"|true",
    })
    void validatesNumericFormats(String format, String value, boolean valid) {
        assertThat(Formats.check(format, json(value)).isEmpty()).as(format + " " + value).isEqualTo(valid);
    }
}
