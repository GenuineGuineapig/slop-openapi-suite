package dev.openapifn.spring.app;

import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives the {@code petAdopted} webhook, shaped like a generated receiver controller. */
@RestController
public class AdoptionReceiver {

    private final OpenApiRuntime runtime;

    public AdoptionReceiver(OpenApiRuntimes runtimes) {
        this.runtime = runtimes.get("pets");
    }

    record Acknowledged(Object body) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    @PostMapping("${openapi-fn.specs.pets.receivers.petAdopted.path:/hooks/adopted}")
    public ResponseEntity<Object> adopted(HttpServletRequest request) {
        var pet = runtime.bind(request, "petAdopted").body(Pet.class);
        return runtime.respond(request, "petAdopted", new Acknowledged(pet.name().equals("broken") ? Map.of("tag", "x") : Map.of("name", pet.name())));
    }
}
