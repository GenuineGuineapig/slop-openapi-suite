package dev.openapifn.schema;

import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonValue;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The standard JSON Schema formats plus the OpenAPI numeric and binary formats. */
public final class Formats {

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?([Zz]|([+-])(\\d{2}):(\\d{2}))");
    private static final Pattern DURATION = Pattern.compile(
        "P(?:(?:\\d+D|\\d+M(?:\\d+D)?|\\d+Y(?:\\d+M(?:\\d+D)?)?)(?:T(?:\\d+H(?:\\d+M(?:\\d+S)?)?|\\d+M(?:\\d+S)?|\\d+S))?"
            + "|T(?:\\d+H(?:\\d+M(?:\\d+S)?)?|\\d+M(?:\\d+S)?|\\d+S)|\\d+W)");
    private static final Pattern RFC_1123 = Pattern.compile(
        "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private static final Pattern IPV4 = Pattern.compile("(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])){3}");
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern RELATIVE_POINTER = Pattern.compile("(0|[1-9][0-9]*)(#|(/([^/~]|~[01])*)*)");
    private static final Pattern LOCAL_PART = Pattern.compile(
        "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"([\\x20\\x21\\x23-\\x5B\\x5D-\\x7E]|\\\\[\\x20-\\x7E])*\"");
    private static final Pattern IDN_LOCAL_PART = Pattern.compile(
        "[A-Za-z0-9!#$%&'*+/=?^_`{|}~\\x{80}-\\x{10FFFF}-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~\\x{80}-\\x{10FFFF}-]+)*"
            + "|\"([\\x20\\x21\\x23-\\x5B\\x5D-\\x7E\\x{80}-\\x{10FFFF}]|\\\\[\\x20-\\x7E\\x{80}-\\x{10FFFF}])*\"");
    private static final Pattern URI_TEMPLATE_VAR = Pattern.compile("(?:[A-Za-z0-9_]|%[0-9A-Fa-f]{2})(?:\\.?(?:[A-Za-z0-9_]|%[0-9A-Fa-f]{2}))*(?::[1-9][0-9]{0,3}|\\*)?");
    private static final BigDecimal INT32_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT32_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal INT64_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal INT64_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal FLOAT_MAX = new BigDecimal(Float.toString(Float.MAX_VALUE));
    private static final BigDecimal DOUBLE_MAX = new BigDecimal(Double.toString(Double.MAX_VALUE));

    /** {@code hostname}: an IDNA2008 host name of A-labels (JSON Schema draft 7 and later, OpenAPI). */
    static final FormatValidator HOSTNAME = FormatValidator.forStrings(value -> Idna.isValid(value, false), "hostname");
    /** {@code hostname} as JSON Schema draft 4 defines it: an RFC 1123 host name of letters, digits and hyphens. */
    static final FormatValidator RFC_1123_HOSTNAME = FormatValidator.forStrings(value -> RFC_1123.matcher(value).matches(), "hostname");

    private static final Map<String, FormatValidator> STANDARD = Map.ofEntries(
        Map.entry("date", FormatValidator.forStrings(Formats::isDate, "RFC 3339 full-date")),
        Map.entry("time", FormatValidator.forStrings(Formats::isTime, "RFC 3339 full-time")),
        Map.entry("date-time", FormatValidator.forStrings(Formats::isDateTime, "RFC 3339 date-time")),
        Map.entry("duration", FormatValidator.forStrings(value -> DURATION.matcher(value).matches(), "ISO 8601 duration")),
        Map.entry("email", FormatValidator.forStrings(value -> isEmail(value, false), "email address")),
        Map.entry("idn-email", FormatValidator.forStrings(value -> isEmail(value, true), "internationalized email address")),
        Map.entry("hostname", HOSTNAME),
        Map.entry("idn-hostname", FormatValidator.forStrings(value -> Idna.isValid(value, true), "internationalized hostname")),
        Map.entry("ipv4", FormatValidator.forStrings(value -> IPV4.matcher(value).matches(), "IPv4 address")),
        Map.entry("ipv6", FormatValidator.forStrings(Formats::isIpv6, "IPv6 address")),
        Map.entry("uri", FormatValidator.forStrings(value -> UriSyntax.isValid(value, true, false), "absolute URI")),
        Map.entry("uri-reference", FormatValidator.forStrings(value -> UriSyntax.isValid(value, false, false), "URI reference")),
        Map.entry("iri", FormatValidator.forStrings(value -> UriSyntax.isValid(value, true, true), "absolute IRI")),
        Map.entry("iri-reference", FormatValidator.forStrings(value -> UriSyntax.isValid(value, false, true), "IRI reference")),
        Map.entry("uri-template", FormatValidator.forStrings(Formats::isUriTemplate, "URI template")),
        Map.entry("json-pointer", FormatValidator.forStrings(value -> JsonPointer.parse(value).isOk(), "JSON pointer")),
        Map.entry("relative-json-pointer", FormatValidator.forStrings(value -> RELATIVE_POINTER.matcher(value).matches(), "relative JSON pointer")),
        Map.entry("regex", FormatValidator.forStrings(value -> EcmaRegex.compile(value).isOk(), "regular expression")),
        Map.entry("uuid", FormatValidator.forStrings(value -> UUID_PATTERN.matcher(value).matches(), "UUID")),
        Map.entry("byte", FormatValidator.forStrings(Formats::isBase64, "base64 encoded value")),
        Map.entry("int32", range("int32", INT32_MIN, INT32_MAX, true)),
        Map.entry("int64", range("int64", INT64_MIN, INT64_MAX, true)),
        Map.entry("float", range("float", FLOAT_MAX.negate(), FLOAT_MAX, false)),
        Map.entry("double", range("double", DOUBLE_MAX.negate(), DOUBLE_MAX, false)));

    private Formats() {
    }

    /** The built-in format validators keyed by format name. Unknown formats are not asserted. */
    public static Map<String, FormatValidator> standard() {
        return STANDARD;
    }

    private static FormatValidator range(String name, BigDecimal min, BigDecimal max, boolean integral) {
        return instance -> instance.asNumber()
            .filter(number -> (integral && !number.isIntegral()) || number.value().compareTo(min) < 0 || number.value().compareTo(max) > 0)
            .map(number -> "must be a valid " + name);
    }

    static boolean isDate(String value) {
        var m = DATE.matcher(value);
        return m.matches() && validDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private static boolean validDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1) {
            return false;
        }
        var leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
        int[] days = {31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day <= days[month - 1];
    }

    static boolean isTime(String value) {
        var m = TIME.matcher(value);
        return m.matches() && validTime(m);
    }

    private static boolean validTime(Matcher m) {
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        int second = Integer.parseInt(m.group(3));
        int offsetHour = m.group(7) == null ? 0 : Integer.parseInt(m.group(7));
        int offsetMinute = m.group(8) == null ? 0 : Integer.parseInt(m.group(8));
        if (hour > 23 || minute > 59 || second > 60 || offsetHour > 23 || offsetMinute > 59) {
            return false;
        }
        if (second == 60) {
            int sign = "-".equals(m.group(6)) ? -1 : 1;
            int utcMinutes = Math.floorMod(hour * 60 + minute - sign * (offsetHour * 60 + offsetMinute), 24 * 60);
            return utcMinutes == 23 * 60 + 59;
        }
        return true;
    }

    static boolean isDateTime(String value) {
        int t = Math.max(value.indexOf('T'), value.indexOf('t'));
        return t > 0 && isDate(value.substring(0, t)) && isTime(value.substring(t + 1));
    }

    static boolean isEmail(String value, boolean international) {
        int at = value.lastIndexOf('@');
        if (at <= 0) {
            return false;
        }
        var local = value.substring(0, at);
        var domain = value.substring(at + 1);
        var localValid = (international ? IDN_LOCAL_PART : LOCAL_PART).matcher(local).matches();
        if (!localValid) {
            return false;
        }
        if (domain.startsWith("[") && domain.endsWith("]")) {
            var literal = domain.substring(1, domain.length() - 1);
            return literal.regionMatches(true, 0, "IPv6:", 0, 5) ? isIpv6(literal.substring(5)) : IPV4.matcher(literal).matches();
        }
        return Idna.isValid(domain, international);
    }

    static boolean isIpv6(String value) {
        if (value.isEmpty() || value.chars().anyMatch(c -> c > 127)) {
            return false;
        }
        var text = value;
        int extraGroups = 0;
        int lastColon = text.lastIndexOf(':');
        if (text.indexOf('.') > lastColon && lastColon >= 0) {
            if (!IPV4.matcher(text.substring(lastColon + 1)).matches()) {
                return false;
            }
            text = text.substring(0, lastColon + 1) + "0";
            extraGroups = 1;
        }
        int compressed = text.indexOf("::");
        if (compressed != text.lastIndexOf("::")) {
            return false;
        }
        if (compressed >= 0) {
            var head = text.substring(0, compressed);
            var tail = text.substring(compressed + 2);
            int count = groups(head) + groups(tail);
            return count >= 0 && count + extraGroups <= 7;
        }
        return groups(text) + extraGroups == 8;
    }

    /** Number of valid hex groups in a colon separated list, or a negative number if invalid. */
    private static int groups(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        var parts = text.split(":", -1);
        for (var part : parts) {
            if (!HEX_GROUP.matcher(part).matches()) {
                return -100;
            }
        }
        return parts.length;
    }

    static boolean isUriTemplate(String value) {
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '}') {
                return false;
            }
            if (c != '{') {
                if (!templateLiteral(value, i)) {
                    return false;
                }
                i += c == '%' ? 3 : 1;
                continue;
            }
            int close = value.indexOf('}', i);
            if (close < 0) {
                return false;
            }
            var expression = value.substring(i + 1, close);
            if (!expression.isEmpty() && "+#./;?&=,!@|".indexOf(expression.charAt(0)) >= 0) {
                expression = expression.substring(1);
            }
            for (var spec : expression.split(",", -1)) {
                if (!URI_TEMPLATE_VAR.matcher(spec).matches()) {
                    return false;
                }
            }
            i = close + 1;
        }
        return true;
    }

    private static boolean templateLiteral(String value, int i) {
        char c = value.charAt(i);
        if (c == '%') {
            return i + 2 < value.length() && Character.digit(value.charAt(i + 1), 16) >= 0 && Character.digit(value.charAt(i + 2), 16) >= 0;
        }
        return c > 0x20 && c != 0x7F && "\"<>\\^`|".indexOf(c) < 0;
    }

    static boolean isBase64(String value) {
        try {
            Base64.getDecoder().decode(value);
            return value.length() % 4 == 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Validates {@code instance} against {@code format} with the standard validators. */
    public static Optional<String> check(String format, JsonValue instance) {
        return Optional.ofNullable(STANDARD.get(format)).flatMap(validator -> validator.validate(instance));
    }
}
