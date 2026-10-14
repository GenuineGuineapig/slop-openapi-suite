package dev.openapifn.spring.webflux.app;

import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.webflux.ReactiveRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;

/** Serves the test description reactively, shaped like generated reactive controllers. */
@RestController
@RequestMapping("${openapi-fn.specs.pets.base-path:/api}")
public class PetsController {

    /** What the controller received, for assertions. */
    public static final List<Object> RECEIVED = new CopyOnWriteArrayList<>();

    private final ReactiveRuntime runtime;

    public PetsController(OpenApiRuntimes runtimes) {
        this.runtime = new ReactiveRuntime(runtimes.get("pets"));
    }

    record Reply(int status, @Nullable Object body, Map<String, @Nullable Object> headers, @Nullable String contentType) implements ApiResponse {

        static Reply of(int status, @Nullable Object body) {
            return new Reply(status, body, Map.of(), null);
        }
    }

    @GetMapping("/pets")
    public Mono<Void> listPets(ServerWebExchange exchange) {
        return runtime.bind(exchange, "listPets").flatMap(binding -> {
            var limit = binding.parameter("query", "limit", Integer.class);
            RECEIVED.add(Objects.requireNonNullElse(limit, 0));
            RECEIVED.add(Objects.requireNonNullElse(binding.parameter("query", "tags", new TypeReference<List<String>>() { }), List.of()));
            var total = limit == null ? 1 : limit;
            var headers = new java.util.LinkedHashMap<String, @Nullable Object>();
            headers.put("X-Total", total);
            headers.put("X-Ids", total == 7 ? null : List.of(1, 2));
            return runtime.respond(exchange, "listPets", new Reply(200, List.of(new Pet(1L, "Rex", null)), headers, null));
        });
    }

    @PostMapping("/pets")
    public Mono<Void> createPet(ServerWebExchange exchange) {
        return runtime.bind(exchange, "createPet").map(binding -> Objects.requireNonNull(binding.body(Pet.class)))
            .flatMap(pet -> runtime.respond(exchange, "createPet", Reply.of(201, new Pet(5L, pet.name(), pet.tag()))));
    }

    @GetMapping("/pets/{id}")
    public Mono<Void> getPet(ServerWebExchange exchange) {
        return runtime.bind(exchange, "getPet").flatMap(binding -> {
            var id = Objects.requireNonNull(binding.parameter("path", "id", Integer.class));
            return runtime.respond(exchange, "getPet", id == 404 ? Reply.of(404, null)
                : id == 2 ? new Reply(200, "Rex", Map.of(), "text/plain")
                : id == 3 ? new Reply(200, Flux.just(Map.of("name", "Rex")), Map.of(), "application/x-ndjson")
                : Reply.of(200, new Pet((long) id, "Rex", null)));
        });
    }

    @DeleteMapping("/pets/{id}")
    public Mono<Void> deletePet(ServerWebExchange exchange) {
        return runtime.bind(exchange, "deletePet").flatMap(binding -> runtime.respond(exchange, "deletePet", Reply.of(204, null)));
    }

    @PutMapping("/pets/{id}/photo")
    public Mono<Void> uploadPhoto(ServerWebExchange exchange) {
        return runtime.bind(exchange, "uploadPhoto").flatMap(binding -> {
            var type = Objects.requireNonNull(exchange.getRequest().getHeaders().getContentType()).toString();
            if (type.startsWith("multipart")) {
                var upload = Objects.requireNonNull(binding.body(Upload.class));
                RECEIVED.add(upload);
                return runtime.respond(exchange, "uploadPhoto", Reply.of(200, upload.file()));
            }
            var file = Objects.requireNonNull(binding.body(FilePart.class));
            return runtime.respond(exchange, "uploadPhoto", Reply.of(200, file));
        });
    }

    @GetMapping("/broken")
    public Mono<Void> broken(ServerWebExchange exchange) {
        return runtime.bind(exchange, "broken").flatMap(binding -> runtime.respond(exchange, "broken", Reply.of(200, Map.of("nope", true))));
    }

    @GetMapping("/undocumented")
    public Mono<String> undocumented() {
        return Mono.just("free");
    }

    /**
     * Streams {@code count} events, one every {@code delay} milliseconds; the item at {@code invalid}
     * violates the schema.
     */
    @GetMapping("/events")
    public Mono<Void> streamEvents(ServerWebExchange exchange) {
        return runtime.bind(exchange, "streamEvents").flatMap(binding -> {
            var format = Objects.requireNonNullElse(binding.parameter("query", "format", String.class), "jsonl");
            var count = Objects.requireNonNullElse(binding.parameter("query", "count", Integer.class), 3);
            var invalid = Objects.requireNonNullElse(binding.parameter("query", "invalid", Integer.class), -1);
            var headers = Boolean.TRUE.equals(binding.parameter("query", "headless", Boolean.class)) ? Map.<String, @Nullable Object>of()
                : Map.<String, @Nullable Object>of("X-Stream", "yes");
            Object events = switch (format) {
                case "sse" -> Flux.range(0, count).map(i -> Map.of("event", "tick", "data", Integer.toString(i)));
                case "mixed" -> Flux.just(Map.of("n", 0), FilePart.of("f.png", "image/png", new byte[] {65}));
                case "slow" -> Flux.interval(Duration.ofMillis(300)).take(count).map(i -> Map.of("n", i));
                case "stream" -> IntStream.range(0, count).mapToObj(i -> Map.of("n", i));
                default -> Flux.range(0, count).map(i -> Map.of("n", i == invalid ? -1 : i));
            };
            var contentType = switch (format) {
                case "sse" -> "text/event-stream";
                case "mixed" -> "multipart/mixed";
                case "json" -> "application/json";
                default -> null;
            };
            return runtime.respond(exchange, "streamEvents", new Reply(200, events, headers, contentType));
        });
    }

    /** Sums the events; mode {@code list} binds them as a list, {@code times} answers when each arrived. */
    @PostMapping("/events")
    public Mono<Void> publishEvents(ServerWebExchange exchange) {
        return runtime.bind(exchange, "publishEvents").flatMap(binding -> {
            var mode = Objects.requireNonNullElse(binding.parameter("query", "mode", String.class), "items");
            if (mode.equals("list")) {
                List<Map<String, Integer>> events = Objects.requireNonNull(binding.body(new TypeReference<List<Map<String, Integer>>>() { }));
                return runtime.respond(exchange, "publishEvents", Reply.of(200, events.stream().mapToInt(event -> event.get("n")).sum()));
            }
            if (mode.equals("times")) {
                var start = System.nanoTime();
                return binding.items(Map.class).map(event -> (System.nanoTime() - start) / 1_000_000).collectList()
                    .flatMap(times -> runtime.respond(exchange, "publishEvents", Reply.of(200, (int) (times.getLast() - times.getFirst()))));
            }
            return binding.items(new TypeReference<Map<String, Integer>>() { }).map(event -> event.get("n")).reduce(0, Integer::sum)
                .flatMap(sum -> runtime.respond(exchange, "publishEvents", Reply.of(200, sum)));
        });
    }
}
