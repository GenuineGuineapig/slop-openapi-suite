package dev.openapifn.load;

import dev.openapifn.fn.Result;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/** URI helpers that tolerate the unescaped characters commonly found in {@code $ref} values. */
public final class Uris {

    /** The scheme of class path resources: {@code classpath:/path/to/file.yaml}. */
    public static final String CLASSPATH_SCHEME = "classpath";

    private static final String ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#@!$&'()*+,;=%";

    private Uris() {
    }

    /**
     * Parses a URI reference, percent-encoding characters that are not legal in URIs (such as
     * spaces, braces and non-ASCII characters) first. {@code classpath:path} is normalized to
     * the hierarchical form {@code classpath:/path}.
     */
    public static Result<URI, String> parse(String reference) {
        var normalized = reference.startsWith(CLASSPATH_SCHEME + ":") && !reference.startsWith(CLASSPATH_SCHEME + ":/")
            ? CLASSPATH_SCHEME + ":/" + reference.substring(CLASSPATH_SCHEME.length() + 1)
            : reference;
        try {
            return Result.ok(new URI(escape(normalized)));
        } catch (URISyntaxException e) {
            return Result.err("Invalid URI reference '" + reference + "': " + e.getReason());
        }
    }

    /** Resolves {@code reference} against {@code base}. */
    public static Result<URI, String> resolve(URI base, String reference) {
        return parse(reference).map(base::resolve);
    }

    /** {@code uri} without its fragment. */
    public static URI withoutFragment(URI uri) {
        if (uri.getRawFragment() == null) {
            return uri;
        }
        var text = uri.toString();
        return URI.create(text.substring(0, text.indexOf('#')));
    }

    /** The decoded fragment of {@code uri}, or the empty string. */
    public static String fragment(URI uri) {
        var fragment = uri.getFragment();
        return fragment == null ? "" : fragment;
    }

    private static String escape(String reference) {
        var out = new StringBuilder(reference.length());
        for (byte b : reference.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && ALLOWED.indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
