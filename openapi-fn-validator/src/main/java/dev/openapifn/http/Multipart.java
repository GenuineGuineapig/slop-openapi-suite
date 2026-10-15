package dev.openapifn.http;

import dev.openapifn.fn.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A minimal RFC 7578 / RFC 2046 multipart parser. */
public final class Multipart {

    private static final Pattern NAME = Pattern.compile("(?i)(?:^|;)\\s*name=(\"([^\"]*)\"|[^;\\s]+)");
    private static final Pattern FILENAME = Pattern.compile("(?i)(?:^|;)\\s*filename=(\"([^\"]*)\"|[^;\\s]+)");

    /**
     * One body part.
     *
     * @param headers part headers
     * @param body part content
     */
    public record Part(Headers headers, byte[] body) {

        /** The {@code name} parameter of {@code Content-Disposition}. */
        public Optional<String> name() {
            return dispositionParameter(NAME);
        }

        /** The {@code filename} parameter of {@code Content-Disposition}. */
        public Optional<String> filename() {
            return dispositionParameter(FILENAME);
        }

        public Optional<String> contentType() {
            return headers.first("Content-Type");
        }

        private Optional<String> dispositionParameter(Pattern pattern) {
            return headers.first("Content-Disposition").map(pattern::matcher).filter(Matcher::find)
                .map(m -> m.group(2) != null ? m.group(2) : m.group(1));
        }
    }

    private Multipart() {
    }

    /** Splits {@code body} into parts delimited by {@code boundary}. */
    public static Result<List<Part>, String> parse(byte[] body, String boundary) {
        var delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        var parts = new ArrayList<Part>();
        int start = indexOf(body, delimiter, 0);
        if (start < 0) {
            return Result.err("multipart body does not contain the boundary '" + boundary + "'");
        }
        int position = start + delimiter.length;
        while (true) {
            if (position + 1 < body.length && body[position] == '-' && body[position + 1] == '-') {
                return Result.ok(parts);
            }
            position = skipLineBreak(body, position);
            int next = indexOf(body, delimiter, position);
            if (next < 0) {
                return Result.err("multipart body is not terminated by the closing boundary");
            }
            int end = body[next - 1] != '\n' ? next : body[next - 2] == '\r' ? next - 2 : next - 1;
            var part = part(Arrays.copyOfRange(body, position, Math.max(position, end)));
            if (part.isErr()) {
                return Result.err(part.failure().orElseThrow());
            }
            parts.add(part.toOptional().orElseThrow());
            position = next + delimiter.length;
        }
    }

    /** A part from its content between delimiters: headers, a blank line and the body. */
    public static Result<Part, String> part(byte[] content) {
        var text = new String(content, StandardCharsets.ISO_8859_1);
        var headerless = text.startsWith("\r\n") ? 2 : text.startsWith("\n") ? 1 : 0;
        if (headerless > 0) {
            return Result.ok(new Part(Headers.EMPTY, Arrays.copyOfRange(content, headerless, content.length)));
        }
        int separator = text.indexOf("\r\n\r\n");
        int separatorLength = 4;
        if (separator < 0) {
            separator = text.indexOf("\n\n");
            separatorLength = 2;
        }
        if (separator < 0) {
            return Result.err("multipart part has no header section");
        }
        var headers = Headers.EMPTY;
        for (var line : text.substring(0, separator).split("\r?\n")) {
            var colon = line.indexOf(':');
            if (colon <= 0) {
                return Result.err("malformed multipart header '" + line + "'");
            }
            headers = headers.with(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        return Result.ok(new Part(headers, Arrays.copyOfRange(content, separator + separatorLength, content.length)));
    }

    private static int skipLineBreak(byte[] body, int position) {
        int p = position;
        while (p < body.length && (body[p] == ' ' || body[p] == '\t')) {
            p++;
        }
        if (p + 1 < body.length && body[p] == '\r' && body[p + 1] == '\n') {
            return p + 2;
        }
        return p < body.length && body[p] == '\n' ? p + 1 : p;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
