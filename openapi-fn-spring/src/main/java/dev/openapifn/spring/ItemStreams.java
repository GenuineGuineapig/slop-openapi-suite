package dev.openapifn.spring;

import dev.openapifn.http.ContentType;
import dev.openapifn.http.ItemFramer;
import dev.openapifn.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/** Reads the items of a body of a sequential media type lazily, see {@link ItemFramer}. */
public final class ItemStreams {

    private ItemStreams() {
    }

    /** Whether bodies of {@code contentType} are streamed rather than read at once: those of sequential media types. */
    public static boolean sequential(@Nullable String contentType) {
        return contentType != null && ContentType.parse(contentType).filter(ItemFramer::isSequential).isPresent();
    }

    /**
     * The items of {@code in}: framed by {@code framer} and parsed (and possibly validated) by
     * {@code item}, which is empty for frames that carry no item. The input is read as the stream is
     * consumed, so items are available as soon as they arrive; closing the stream closes the input.
     */
    public static Stream<JsonValue> read(InputStream in, ItemFramer framer, Function<ItemFramer.Frame, Optional<JsonValue>> item) {
        var items = new Spliterators.AbstractSpliterator<JsonValue>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
            private final ArrayDeque<ItemFramer.Frame> frames = new ArrayDeque<>();
            private final byte[] buffer = new byte[8192];
            private boolean finished;

            @Override
            public boolean tryAdvance(Consumer<? super JsonValue> action) {
                while (true) {
                    var frame = frames.poll();
                    if (frame != null) {
                        var value = item.apply(frame);
                        if (value.isPresent()) {
                            action.accept(value.get());
                            return true;
                        }
                    } else if (finished) {
                        return false;
                    } else {
                        read();
                    }
                }
            }

            private void read() {
                try {
                    var count = in.read(buffer);
                    if (count < 0) {
                        finished = true;
                        frames.add(framer.finish());
                    } else {
                        frames.addAll(framer.feed(buffer, 0, count));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
        return StreamSupport.stream(items, false).onClose(() -> close(in));
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
