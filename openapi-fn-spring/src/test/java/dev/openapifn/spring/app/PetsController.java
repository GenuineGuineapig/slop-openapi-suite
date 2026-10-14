package dev.openapifn.spring.app;

import dev.openapifn.spring.ApiResponse;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.servlet.ServletRuntime;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

@RestController
@RequestMapping("${openapi-fn.specs.pets.base-path:/api}")
public class PetsController {

    private final ServletRuntime runtime;

    public PetsController(OpenApiRuntimes runtimes) {
        this.runtime = new ServletRuntime(runtimes.get("pets"));
    }

    record ListOk(List<Pet> body, Integer xTotal, @Nullable List<Integer> xIds) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }

        @Override
        public Map<String, @Nullable Object> headers() {
            var headers = new LinkedHashMap<String, @Nullable Object>();
            headers.put("X-Total", xTotal);
            headers.put("X-Ids", xIds);
            return headers;
        }
    }

    record Created(Pet body) implements ApiResponse {
        @Override
        public int status() {
            return 201;
        }
    }

    record Found(Object body, @Nullable String contentType) implements ApiResponse {
        @Override
        public int status() {
            return 200;
        }
    }

    record Status(int status) implements ApiResponse {
    }

    @GetMapping("/pets")
    public ResponseEntity<Object> listPets(HttpServletRequest request) {
        var binding = runtime.bind(request, "listPets");
        Calls.RECEIVED.add(binding.parameter("query", "limit", Integer.class));
        Calls.RECEIVED.add(binding.parameter("query", "tags", new TypeReference<List<String>>() { }));
        Calls.RECEIVED.add(binding.parameter("header", "X-Trace", String.class));
        Calls.RECEIVED.add(binding.parameter("cookie", "session", String.class));
        var limit = binding.parameter("query", "limit", Integer.class);
        var total = limit == null ? 1 : limit;
        return runtime.respond(request, "listPets", new ListOk(List.of(new Pet(1L, "Rex", null)), total, total == 7 ? null : List.of(1, 2)));
    }

    @PostMapping("/pets")
    public ResponseEntity<Object> createPet(HttpServletRequest request) {
        var pet = runtime.bind(request, "createPet").body(Pet.class);
        Calls.RECEIVED.add(pet);
        return runtime.respond(request, "createPet", new Created(new Pet(5L, pet.name(), pet.tag())));
    }

    @GetMapping("/pets/{id}")
    public ResponseEntity<Object> getPet(HttpServletRequest request) {
        var id = runtime.bind(request, "getPet").parameter("path", "id", Long.class);
        Calls.RECEIVED.add(id);
        if (id == 404) {
            return runtime.respond(request, "getPet", new Status(404));
        }
        return runtime.respond(request, "getPet", new Found(new Pet(id, "Rex", "dog"), id == 2 ? "text/plain" : null));
    }

    @DeleteMapping("/pets/{id}")
    public ResponseEntity<Object> deletePet(HttpServletRequest request) {
        Calls.RECEIVED.add(runtime.bind(request, "deletePet").parameter("path", "id", Long.class));
        return runtime.respond(request, "deletePet", new Status(204));
    }

    @PutMapping("/pets/{id}/photo")
    public ResponseEntity<Object> uploadPhoto(HttpServletRequest request) {
        var binding = runtime.bind(request, "uploadPhoto");
        Calls.RECEIVED.add(binding.parameter("path", "id", Integer.class));
        var type = request.getContentType();
        if (type.startsWith("multipart")) {
            var upload = binding.body(Upload.class);
            Calls.RECEIVED.add(upload);
            return runtime.respond(request, "uploadPhoto", new Found(upload.file(), null));
        }
        if (type.startsWith("image")) {
            var bytes = binding.body(byte[].class);
            Calls.RECEIVED.add(bytes);
            Calls.RECEIVED.add(binding.body(FilePart.class));
            return runtime.respond(request, "uploadPhoto", new Found(bytes, null));
        }
        var text = binding.body(String.class);
        Calls.RECEIVED.add(text);
        return runtime.respond(request, "uploadPhoto", new Found(List.of(Map.of("text", text)), "application/jsonl"));
    }

    @GetMapping("/broken")
    public ResponseEntity<Object> broken(HttpServletRequest request) {
        runtime.bind(request, "broken");
        return runtime.respond(request, "broken", new Found(Map.of("nope", true), null));
    }

    @GetMapping("/undocumented")
    public String undocumented() {
        return "free";
    }
}
