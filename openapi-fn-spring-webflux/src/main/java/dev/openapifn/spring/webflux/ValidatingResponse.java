package dev.openapifn.spring.webflux;

import dev.openapifn.http.ItemFramer;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.OperationMatch;
import dev.openapifn.validation.ValidationReport;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validates a response while it is written. A body of a sequential media type described for the
 * status is validated item by item, and only valid items are passed on; other bodies are
 * collected and validated whole before they are written.
 */
final class ValidatingResponse extends ServerHttpResponseDecorator {

    private final ServerWebExchange exchange;
    private final OpenApiValidator validator;
    private final OperationMatch match;
    private final boolean fail;
    private final Consumer<ValidationReport> log;

    /**
     * @param fail whether an invalid response becomes a problem (or ends the stream)
     * @param log receives the findings
     */
    ValidatingResponse(ServerWebExchange exchange, OpenApiValidator validator, OperationMatch match, boolean fail, Consumer<ValidationReport> log) {
        super(exchange.getResponse());
        this.exchange = exchange;
        this.validator = validator;
        this.match = match;
        this.fail = fail;
        this.log = log;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        if (rejected()) {
            return super.writeWith(body);
        }
        var items = items();
        if (items.isPresent()) {
            return stream(Flux.from(body).map(Mono::just), items.get());
        }
        return DataBufferUtils.join(body).map(ReactiveMessages::bytes).defaultIfEmpty(new byte[0]).flatMap(this::whole);
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        if (rejected()) {
            return super.writeAndFlushWith(body);
        }
        var items = items();
        if (items.isPresent()) {
            return stream(Flux.from(body), items.get());
        }
        return writeWith(Flux.from(body).concatMap(Flux::from));
    }

    @Override
    public Mono<Void> setComplete() {
        return rejected() || isCommitted() ? super.setComplete() : whole(new byte[0]);
    }

    /** Whether the request was rejected while it was bound: the response is a problem, not one of the operation. */
    private boolean rejected() {
        return exchange.getAttribute(OpenApiRuntime.REJECTED_ATTRIBUTE) != null;
    }

    /** The item validator of the body, when it is of a sequential media type described for the status. */
    private Optional<OpenApiValidator.ItemValidator> items() {
        var contentType = getHeaders().getFirst("Content-Type");
        return contentType == null ? Optional.empty() : validator.responseItems(match, ReactiveMessages.response(this, new byte[0]).status(), contentType);
    }

    private Mono<Void> whole(byte[] body) {
        var report = validator.validateResponse(match, ReactiveMessages.response(this, body));
        if (!report.isEmpty()) {
            log.accept(report);
        }
        if (report.hasErrors() && fail) {
            return failed(report);
        }
        return body.length == 0 ? super.setComplete() : super.writeWith(Mono.just(bufferFactory().wrap(body)));
    }

    private Mono<Void> failed(ValidationReport report) {
        return OpenApiValidationWebFilter.problem(getDelegate(), 500, "Response validation failed", exchange.getRequest().getPath().value(), report);
    }

    /** What passes of a chunk of the body: the bytes of its valid items, and the findings that ended the stream. */
    private record Passed(byte[] bytes, @Nullable ValidationReport failure) {
    }

    /** Validates the head, then each item as its chunk completes it, passing on the valid ones (and flushing per chunk). */
    private Mono<Void> stream(Flux<? extends Publisher<? extends DataBuffer>> chunks, OpenApiValidator.ItemValidator items) {
        var head = validator.validateResponseHead(match, ReactiveMessages.response(this, new byte[0]));
        if (!head.isEmpty()) {
            log.accept(head);
        }
        if (head.hasErrors() && fail) {
            return failed(head);
        }
        var framer = items.framer();
        var passing = new Passing(items);
        var sent = new AtomicBoolean();
        Flux<Mono<DataBuffer>> passed = chunks.concatMap(chunk -> Flux.from(chunk).map(ReactiveMessages::bytes).collectList()
                .map(buffers -> passing.pass(buffers.stream().flatMap(bytes -> framer.feed(bytes).stream()).toList())))
            .concatWith(Mono.fromSupplier(() -> passing.pass(List.of(framer.finish()))))
            .handle((chunk, sink) -> {
                if (chunk.failure() != null && chunk.bytes().length == 0 && !sent.get()) {
                    // Nothing is committed before the first chunk: the error lets the response become a problem.
                    sink.error(new Rejected(chunk.failure()));
                    return;
                }
                if (chunk.bytes().length > 0) {
                    sent.set(true);
                    sink.next(Mono.just(bufferFactory().wrap(chunk.bytes())));
                }
                if (chunk.failure() != null) {
                    sink.complete();
                }
            });
        return super.writeAndFlushWith(passed).onErrorResume(Rejected.class, rejected -> failed(rejected.report));
    }

    /** Signals that the first item of a stream is invalid, before anything was sent. */
    private static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ValidationReport report;

        Rejected(ValidationReport report) {
            super("the first item of the stream is invalid", null, false, false);
            this.report = report;
        }
    }

    /** Validates frames until one fails (when failing). */
    private final class Passing {

        private final OpenApiValidator.ItemValidator items;
        private @Nullable ValidationReport failure;

        Passing(OpenApiValidator.ItemValidator items) {
            this.items = items;
        }

        Passed pass(List<ItemFramer.Frame> frames) {
            var out = new ByteArrayOutputStream();
            for (var frame : frames) {
                if (failure == null) {
                    var report = items.validate(frame).report();
                    if (!report.isEmpty()) {
                        log.accept(report);
                    }
                    if (fail && report.hasErrors()) {
                        failure = report;
                    } else {
                        out.writeBytes(frame.raw());
                    }
                }
            }
            return new Passed(out.toByteArray(), failure);
        }
    }
}
