package dev.openapifn.spring.webflux.app;

import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.webflux.ReactiveRuntime;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Receives the {@code petAdopted} webhook, shaped like a generated reactive receiver controller. */
@RestController
public class AdoptionReceiver {

    private final ReactiveRuntime runtime;

    public AdoptionReceiver(OpenApiRuntimes runtimes) {
        this.runtime = new ReactiveRuntime(runtimes.get("pets"));
    }

    record Acknowledged(Object body) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    @PostMapping("${openapi-fn.specs.pets.receivers.petAdopted.path:/hooks/adopted}")
    public Mono<Void> adopted(ServerWebExchange exchange) {
        return runtime.bind(exchange, "petAdopted").map(binding -> Objects.requireNonNull(binding.body(Pet.class)))
            .flatMap(pet -> runtime.respond(exchange, "petAdopted", new Acknowledged(pet.name().equals("broken") ? Map.of("tag", "x")
                : Map.of("name", pet.name()))));
    }
}
