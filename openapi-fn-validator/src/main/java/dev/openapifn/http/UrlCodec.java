package dev.openapifn.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Percent-encoding, query strings and cookie headers. */
public final class UrlCodec {

    /** RFC 3986 unreserved characters. */
    public static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    /** RFC 3986 reserved characters. */
    public static final String RESERVED = ":/?#[]@!$&'()*+,;=";

    private UrlCodec() {
    }

    /**
     * Decodes percent-escapes as UTF-8. Malformed escapes are kept literally. With
     * {@code plusAsSpace}, {@code +} decodes to a space (form encoding).
     */
    public static String decode(String text, boolean plusAsSpace) {
        if (text.indexOf('%') < 0 && (!plusAsSpace || text.indexOf('+') < 0)) {
            return text;
        }
        var out = new ByteArrayOutputStream(text.length());
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b == '%' && i + 2 < bytes.length && hex(bytes[i + 1]) >= 0 && hex(bytes[i + 2]) >= 0) {
                out.write(hex(bytes[i + 1]) * 16 + hex(bytes[i + 2]));
                i += 2;
            } else {
                out.write(plusAsSpace && b == '+' ? ' ' : b);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(byte b) {
        return Character.digit(b, 16);
    }

    /** Percent-encodes everything except unreserved characters and those in {@code allowed}. */
    public static String encode(String text, String allowed) {
        var out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (UNRESERVED.indexOf(c) >= 0 || allowed.indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /**
     * Encodes for {@code application/x-www-form-urlencoded} as the WHATWG URL standard does: ASCII
     * letters, digits and {@code *-._} stay, a space becomes {@code +}, everything else is
     * percent-encoded (unlike RFC 3986 query values, where {@code ~} stays and a space is {@code %20}).
     */
    public static String encodeForm(String text) {
        var out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c == ' ') {
                out.append('+');
            } else if (c < 0x80 && (Character.isLetterOrDigit(c) || "*-._".indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** Splits a query string ({@code a=1&b=2}) into decoded name/value pairs; {@code +} is a space. */
    public static List<Map.Entry<String, String>> parseQuery(String query) {
        var pairs = new ArrayList<Map.Entry<String, String>>();
        for (var part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            var eq = part.indexOf('=');
            var name = eq < 0 ? part : part.substring(0, eq);
            var value = eq < 0 ? "" : part.substring(eq + 1);
            pairs.add(Map.entry(decode(name, true), decode(value, true)));
        }
        return pairs;
    }

    /** Splits {@code Cookie} header values into raw (undecoded) name/value pairs. */
    public static List<Map.Entry<String, String>> parseCookies(List<String> headerValues) {
        var cookies = new ArrayList<Map.Entry<String, String>>();
        for (var header : headerValues) {
            for (var part : header.split(";")) {
                var eq = part.indexOf('=');
                if (eq > 0) {
                    cookies.add(Map.entry(part.substring(0, eq).trim(), part.substring(eq + 1).trim()));
                }
            }
        }
        return cookies;
    }
}
