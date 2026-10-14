package dev.openapifn.spring.servlet;

import dev.openapifn.http.ItemFramer;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Captures a response for validation once it is complete, like
 * {@link ContentCachingResponseWrapper}, unless it streams a body of a sequential media type that
 * is described for its status. Such a body is validated while it is written: the status, headers
 * and content type when the body is opened, then each item once it is complete. Only valid items
 * are passed on (and flushed when the application flushes); when failing on invalid responses, the
 * first invalid item ends the stream.
 */
final class ValidatingResponseWrapper extends ContentCachingResponseWrapper {

    private final OpenApiValidator validator;
    private final OperationMatch match;
    private final boolean fail;
    private final Consumer<ValidationReport> log;
    private boolean decided;
    private @Nullable Streaming streaming;
    private @Nullable PrintWriter writer;

    /**
     * @param fail whether an invalid head or item ends the stream
     * @param log receives the findings of the head and of each item
     */
    ValidatingResponseWrapper(HttpServletResponse response, OpenApiValidator validator, OperationMatch match, boolean fail,
                              Consumer<ValidationReport> log) {
        super(response);
        this.validator = validator;
        this.match = match;
        this.fail = fail;
        this.log = log;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        var stream = streaming();
        return stream != null ? stream : super.getOutputStream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        var stream = streaming();
        if (stream == null) {
            return super.getWriter();
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(stream, getCharacterEncoding()));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (streaming == null) {
            super.flushBuffer();
            return;
        }
        if (writer != null) {
            writer.flush();
        }
        streaming.flush();
    }

    /** Whether the body is streamed (rather than captured). */
    boolean streamed() {
        return streaming != null;
    }

    /**
     * Ends a streamed body: validates and passes on what remains of it.
     *
     * @return the findings that ended the stream, if any
     */
    Optional<ValidationReport> finish() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        var stream = java.util.Objects.requireNonNull(streaming);
        stream.pass(stream.framer.finish());
        return Optional.ofNullable(stream.failure);
    }

    /** The streaming body, decided when the body is first opened: by then status and content type are set. */
    private @Nullable Streaming streaming() throws IOException {
        if (!decided) {
            decided = true;
            var contentType = getContentType();
            var items = contentType == null ? Optional.<OpenApiValidator.ItemValidator>empty() : validator.responseItems(match, getStatus(), contentType);
            if (items.isPresent()) {
                streaming = new Streaming(getResponse().getOutputStream(), items.get());
                streaming.report(validator.validateResponseHead(match, ServletMessages.response(this)));
            }
        }
        return streaming;
    }

    /** Passes on the frames of the body that are valid, until one is not (when failing). */
    private final class Streaming extends ServletOutputStream {

        private final ServletOutputStream target;
        private final OpenApiValidator.ItemValidator items;
        private final ItemFramer framer;
        private @Nullable ValidationReport failure;

        Streaming(ServletOutputStream target, OpenApiValidator.ItemValidator items) {
            this.target = target;
            this.items = items;
            this.framer = items.framer();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (var frame : framer.feed(bytes, offset, length)) {
                pass(frame);
            }
        }

        void pass(ItemFramer.Frame frame) throws IOException {
            if (failure == null) {
                report(items.validate(frame).report());
            }
            if (failure == null) {
                target.write(frame.raw());
            }
        }

        void report(ValidationReport report) {
            if (!report.isEmpty()) {
                log.accept(report);
            }
            if (fail && report.hasErrors()) {
                failure = report;
            }
        }

        @Override
        public void flush() throws IOException {
            if (failure == null) {
                target.flush();
            }
        }

        @Override
        public boolean isReady() {
            return target.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            target.setWriteListener(listener);
        }
    }
}
