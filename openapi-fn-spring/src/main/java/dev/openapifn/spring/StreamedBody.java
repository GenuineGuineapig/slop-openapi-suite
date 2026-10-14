package dev.openapifn.spring;

import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A body of a sequential media type whose items are written, and flushed, one by one as the
 * stream produces them (see {@link dev.openapifn.http.ItemFramer}). Written by
 * {@link StreamedBodyConverter} on the server and as a streaming request body by clients.
 */
public final class StreamedBody {

    private final Stream<?> items;
    private final Function<Object, byte[]> encoder;
    private final byte[] end;

    /**
     * @param items the items; the stream is closed once written
     * @param encoder an item as the bytes of the body
     * @param end what follows the last item
     */
    public StreamedBody(Stream<?> items, Function<Object, byte[]> encoder, byte[] end) {
        this.items = items;
        this.encoder = encoder;
        this.end = end.clone();
    }

    /** Writes the items to {@code out}, flushing it after each one. */
    public void writeTo(OutputStream out) throws IOException {
        writeTo(out, out);
    }

    /** Writes the items to {@code out}, flushing {@code flushable} (which passes them on) after each one. */
    public void writeTo(OutputStream out, Flushable flushable) throws IOException {
        try (items) {
            var iterator = items.iterator();
            while (iterator.hasNext()) {
                out.write(encoder.apply(iterator.next()));
                flushable.flush();
            }
        }
        out.write(end);
        flushable.flush();
    }
}
