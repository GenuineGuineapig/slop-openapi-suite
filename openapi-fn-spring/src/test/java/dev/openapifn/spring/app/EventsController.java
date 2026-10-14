package dev.openapifn.spring.app;

import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

/** Streams sequential bodies in both directions. */
@RestController
@RequestMapping("${openapi-fn.specs.pets.base-path:/api}")
public class EventsController {

    /** Released by a client once it has received the first streamed item. */
    public static volatile CountDownLatch received = new CountDownLatch(1);

    private final OpenApiRuntime runtime;

    public EventsController(OpenApiRuntimes runtimes) {
        this.runtime = runtimes.get("pets");
    }

    record Events(Object body, Map<String, @Nullable Object> headers, @Nullable String contentType) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    record Sum(Integer body) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    /**
     * Streams {@code count} events; the item at {@code invalid} violates the schema. The format
     * {@code wait} streams one event and then waits for {@link #received} before the second, which
     * says whether it was released in time.
     */
    @GetMapping("/events")
    public ResponseEntity<Object> streamEvents(HttpServletRequest request) {
        var binding = runtime.bind(request, "streamEvents");
        var format = Objects.requireNonNullElse(binding.parameter("query", "format", String.class), "jsonl");
        var count = Objects.requireNonNullElse(binding.parameter("query", "count", Integer.class), 3);
        var invalid = Objects.requireNonNullElse(binding.parameter("query", "invalid", Integer.class), -1);
        var headers = Boolean.TRUE.equals(binding.parameter("query", "headless", Boolean.class)) ? Map.<String, @Nullable Object>of()
            : Map.<String, @Nullable Object>of("X-Stream", "yes");
        Stream<?> events = switch (format) {
            case "sse" -> IntStream.range(0, count).mapToObj(i -> Map.of("event", "tick", "data", Integer.toString(i)));
            case "mixed" -> Stream.of(Map.of("n", 0), FilePart.of("f.png", "image/png", new byte[] {65}));
            case "wait" -> Stream.of(0, 1).map(i -> Map.of("n", i == 0 ? 0 : released() ? 1 : 99));
            default -> IntStream.range(0, count).mapToObj(i -> Map.of("n", i == invalid ? -1 : i));
        };
        var contentType = switch (format) {
            case "sse" -> "text/event-stream";
            case "mixed" -> "multipart/mixed";
            case "json" -> "application/json";
            default -> null;
        };
        return runtime.respond(request, "streamEvents", new Events(events, headers, contentType));
    }

    private static boolean released() {
        try {
            return received.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Sums the events; mode {@code list} binds them as a list, {@code wait} releases {@link #received} on the first event. */
    @PostMapping("/events")
    public ResponseEntity<Object> publishEvents(HttpServletRequest request) {
        var binding = runtime.bind(request, "publishEvents");
        var mode = Objects.requireNonNullElse(binding.parameter("query", "mode", String.class), "items");
        if (mode.equals("list")) {
            List<Map<String, Integer>> events = Objects.requireNonNull(binding.body(new TypeReference<List<Map<String, Integer>>>() { }));
            return runtime.respond(request, "publishEvents", new Sum(events.stream().mapToInt(event -> event.get("n")).sum()));
        }
        try (var events = binding.items(new TypeReference<Map<String, Integer>>() { })) {
            var sum = events.peek(event -> {
                if (mode.equals("wait")) {
                    received.countDown();
                }
            }).mapToInt(event -> event.get("n")).sum();
            return runtime.respond(request, "publishEvents", new Sum(sum));
        }
    }
}
