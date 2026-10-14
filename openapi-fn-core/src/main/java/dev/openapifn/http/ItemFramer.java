package dev.openapifn.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Splits a body of a sequential media type into its items while the bytes arrive, so that items
 * can be parsed, validated and passed on one by one:
 * <ul>
 *   <li>{@code application/jsonl}, {@code application/x-ndjson}, {@code application/x-jsonlines}:
 *       one item per line</li>
 *   <li>{@code application/json-seq} (RFC 7464): records introduced by the record separator; a
 *       record ends at the separator of the next one or at the line feed after a complete JSON
 *       text</li>
 *   <li>{@code text/event-stream}: events terminated by a blank line</li>
 *   <li>other {@code multipart} types than {@code form-data} (with a {@code boundary}): positional
 *       parts, each complete once the next delimiter arrives</li>
 * </ul>
 * Every byte fed belongs to exactly one frame, so the concatenated {@link Frame#raw()} bytes of
 * all frames (including the one {@link #finish()} returns) are the body as it was fed.
 */
public final class ItemFramer {

    private static final Set<String> LINE_DELIMITED = Set.of("application/jsonl", "application/x-ndjson", "application/x-jsonlines");
    private static final byte RECORD_SEPARATOR = 0x1e;
    private static final byte[] NONE = new byte[0];

    /**
     * A part of the body.
     *
     * @param raw the bytes of the body this frame consumed, delimiters included
     * @param content the item's content without delimiters (headers and body for multipart parts);
     *     blank for frames that carry no item, such as empty lines
     * @param error why the body ended badly (an unterminated multipart body); only on the last frame
     */
    public record Frame(byte[] raw, byte[] content, Optional<String> error) {

        /** Whether the content is empty or white space. */
        public boolean blank() {
            for (var b : content) {
                if (!whitespace(b)) {
                    return false;
                }
            }
            return true;
        }
    }

    private enum Kind { LINES, RECORDS, EVENTS, PARTS }

    private enum PartState { PREAMBLE, AFTER_DELIMITER, IN_PART, CLOSED }

    private final Kind kind;
    private final byte[] delimiter;
    private final java.util.function.Consumer<List<Frame>> scanner;
    private byte[] buffer = new byte[512];
    private int length;
    private int rawStart;
    private int contentStart;
    private int scanned;
    private int lineStart;
    private int depth;
    private boolean inString;
    private boolean escaped;
    private boolean content;
    private PartState partState = PartState.PREAMBLE;

    private ItemFramer(Kind kind, byte[] delimiter) {
        this.kind = kind;
        this.delimiter = delimiter;
        this.scanner = switch (kind) {
            case LINES -> this::lines;
            case RECORDS -> this::records;
            case EVENTS -> this::events;
            case PARTS -> this::parts;
        };
    }

    /** Whether {@code type} is a sequential media type this class can frame (multipart types need their {@code boundary}). */
    public static boolean isSequential(ContentType type) {
        return of(type).isPresent();
    }

    /**
     * Whether a described media type is a sequential one, whose bodies are sequences of items; unlike
     * {@link #isSequential}, multipart types need no {@code boundary} (it is chosen per message).
     */
    public static boolean isSequentialMediaType(ContentType type) {
        return LINE_DELIMITED.contains(type.essence()) || type.essence().equals("application/json-seq") || type.essence().equals("text/event-stream")
            || type.type().equals("multipart") && !type.subtype().equals("form-data") && !type.subtype().equals("*");
    }

    /** A framer for bodies of {@code type}, if it is a sequential media type (multipart needs its {@code boundary}). */
    public static Optional<ItemFramer> of(ContentType type) {
        var essence = type.essence();
        if (LINE_DELIMITED.contains(essence)) {
            return Optional.of(new ItemFramer(Kind.LINES, NONE));
        }
        if (essence.equals("application/json-seq")) {
            return Optional.of(new ItemFramer(Kind.RECORDS, NONE));
        }
        if (essence.equals("text/event-stream")) {
            return Optional.of(new ItemFramer(Kind.EVENTS, NONE));
        }
        if (type.type().equals("multipart") && !type.subtype().equals("form-data")) {
            return type.parameter("boundary").map(boundary -> new ItemFramer(Kind.PARTS, ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1)));
        }
        return Optional.empty();
    }

    /** Frames all of {@code body}, including the last frame. */
    public List<Frame> frames(byte[] body) {
        var frames = new ArrayList<>(feed(body));
        frames.add(finish());
        return frames;
    }

    public List<Frame> feed(byte[] bytes) {
        return feed(bytes, 0, bytes.length);
    }

    /** Adds bytes and returns the frames they complete. */
    public List<Frame> feed(byte[] bytes, int offset, int count) {
        append(bytes, offset, count);
        var frames = new ArrayList<Frame>();
        scanner.accept(frames);
        return frames;
    }

    /** The rest of the body as the last frame; its content is the unterminated last item, if any. */
    public Frame finish() {
        var raw = Arrays.copyOfRange(buffer, rawStart, length);
        var rest = Arrays.copyOfRange(buffer, Math.min(contentStart, length), length);
        rawStart = contentStart = scanned = lineStart = length;
        return switch (kind) {
            case LINES -> new Frame(raw, stripCarriageReturn(rest), Optional.empty());
            case RECORDS, EVENTS -> new Frame(raw, rest, Optional.empty());
            case PARTS -> new Frame(raw, NONE, switch (partState) {
                case PREAMBLE -> Optional.of("multipart body does not contain the boundary '"
                    + new String(delimiter, 2, delimiter.length - 2, StandardCharsets.ISO_8859_1) + "'");
                case CLOSED -> Optional.empty();
                default -> Optional.of("multipart body is not terminated by the closing boundary");
            });
        };
    }

    // ---------------------------------------------------------------------------------------------

    private void lines(List<Frame> frames) {
        for (int i = scanned; i < length; i++) {
            if (buffer[i] == '\n') {
                frames.add(frame(i + 1, stripCarriageReturn(Arrays.copyOfRange(buffer, rawStart, i))));
            }
        }
        scanned = length;
    }

    /** Records of a JSON text sequence, tracking JSON nesting to find a record's end without waiting for the next one. */
    private void records(List<Frame> frames) {
        for (int i = scanned; i < length; i++) {
            var b = buffer[i];
            if (b == RECORD_SEPARATOR) {
                if (content) {
                    frames.add(frame(i, Arrays.copyOfRange(buffer, contentStart, i)));
                }
                resetJson();
                contentStart = i + 1;
            } else if (inString) {
                inString = escaped || b != '"';
                escaped = !escaped && b == '\\';
            } else if (b == '"') {
                inString = content = true;
            } else if (b == '{' || b == '[') {
                depth++;
                content = true;
            } else if (b == '}' || b == ']') {
                depth--;
            } else if (b == '\n' && depth <= 0 && content) {
                frames.add(frame(i + 1, Arrays.copyOfRange(buffer, contentStart, i)));
                resetJson();
                contentStart = i + 1;
            } else if (!whitespace(b)) {
                content = true;
            }
        }
        scanned = length;
    }

    private void resetJson() {
        depth = 0;
        inString = escaped = content = false;
    }

    /** Events: lines up to a blank line. */
    private void events(List<Frame> frames) {
        for (int i = scanned; i < length; i++) {
            if (buffer[i] == '\n') {
                var empty = i == lineStart || i == lineStart + 1 && buffer[lineStart] == '\r';
                if (empty) {
                    frames.add(frame(i + 1, Arrays.copyOfRange(buffer, rawStart, lineStart)));
                }
                lineStart = i + 1;
            }
        }
        scanned = length;
    }

    private void parts(List<Frame> frames) {
        while (true) {
            switch (partState) {
                case PREAMBLE, IN_PART -> {
                    var position = delimiter(Math.max(scanned, contentStart));
                    if (position < 0) {
                        scanned = Math.max(contentStart, length - delimiter.length);
                        return;
                    }
                    if (partState == PartState.IN_PART) {
                        var end = position - 2 >= contentStart && buffer[position - 2] == '\r' ? position - 2 : position - 1;
                        frames.add(frame(position + delimiter.length, Arrays.copyOfRange(buffer, contentStart, Math.max(contentStart, end))));
                    }
                    partState = PartState.AFTER_DELIMITER;
                    contentStart = scanned = position + delimiter.length;
                }
                case AFTER_DELIMITER -> {
                    if (length - contentStart < 2) {
                        return;
                    }
                    if (buffer[contentStart] == '-' && buffer[contentStart + 1] == '-') {
                        partState = PartState.CLOSED;
                        return;
                    }
                    var lineEnd = indexOf((byte) '\n', contentStart);
                    if (lineEnd < 0) {
                        return;
                    }
                    partState = PartState.IN_PART;
                    contentStart = scanned = lineEnd + 1;
                }
                default -> {
                    return;
                }
            }
        }
    }

    /**
     * The next delimiter at the start of the body or of a line, from {@code from}. (Index 0 is the start of the body: consumed
     * bytes are only dropped after the first delimiter, and the line break before a part's content is kept.)
     */
    private int delimiter(int from) {
        outer:
        for (int i = from; i <= length - delimiter.length; i++) {
            if (i > 0 && buffer[i - 1] != '\n') {
                continue;
            }
            for (int j = 0; j < delimiter.length; j++) {
                if (buffer[i + j] != delimiter[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private int indexOf(byte b, int from) {
        for (int i = from; i < length; i++) {
            if (buffer[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** A frame of the raw bytes up to {@code rawEnd}. */
    private Frame frame(int rawEnd, byte[] itemContent) {
        var frame = new Frame(Arrays.copyOfRange(buffer, rawStart, rawEnd), itemContent, Optional.empty());
        rawStart = rawEnd;
        if (kind != Kind.PARTS) {
            contentStart = Math.max(contentStart, rawEnd);
            lineStart = Math.max(lineStart, rawEnd);
        }
        return frame;
    }

    private void append(byte[] bytes, int offset, int count) {
        if (length + count > buffer.length) {
            var keep = Math.min(rawStart, contentStart);
            if (keep > 0) {
                System.arraycopy(buffer, keep, buffer, 0, length - keep);
                length -= keep;
                rawStart -= keep;
                contentStart -= keep;
                scanned -= keep;
                lineStart -= keep;
            }
            if (length + count > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + count));
            }
        }
        System.arraycopy(bytes, offset, buffer, length, count);
        length += count;
    }

    private static byte[] stripCarriageReturn(byte[] line) {
        return line.length > 0 && line[line.length - 1] == '\r' ? Arrays.copyOf(line, line.length - 1) : line;
    }

    private static boolean whitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }
}
