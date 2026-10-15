package dev.openapifn.spring.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.openapifn.spring.OpenApiRuntimes;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = OpenApiMatchersTest.App.class, properties = "openapi-fn.validation.request.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
class OpenApiMatchersTest {

    @SpringBootApplication
    @RestController
    static class App {

        @GetMapping("/api/pets/{id}")
        ResponseEntity<Map<String, Object>> pet() {
            return ResponseEntity.ok(Map.of("name", "Rex"));
        }

        @GetMapping("/api/broken")
        ResponseEntity<Map<String, Object>> broken() {
            return ResponseEntity.ok(Map.of());
        }

        @PostMapping("/api/pets")
        ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> pet) {
            return ResponseEntity.status(201).body(Map.of("name", pet.get("name")));
        }

        @DeleteMapping("/api/pets/{id}")
        ResponseEntity<Void> delete() {
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/hooks/adopted")
        ResponseEntity<Map<String, Object>> adopted(@RequestBody Map<String, Object> pet) {
            return ResponseEntity.ok(pet);
        }

        @PutMapping("/api/pets/{id}/photo")
        ResponseEntity<byte[]> photo() {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(new byte[] {1});
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    OpenApiRuntimes runtimes;

    @Test
    void acceptsConformingExchanges() throws Exception {
        var pets = runtimes.get("pets");
        mvc.perform(get("/api/pets/1")).andExpect(OpenApiMatchers.conformsTo(pets)).andExpect(OpenApiMatchers.responseConformsTo(pets));
        mvc.perform(MockMvcRequestBuilders.delete("/api/pets/1")).andExpect(OpenApiMatchers.conformsTo(pets));
        mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\"}"))
            .andExpect(OpenApiMatchers.conformsTo(pets));
        mvc.perform(multipart("/api/pets/.1/photo").part(new MockPart("file", "f.png", new byte[] {1}, MediaType.IMAGE_PNG))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
            .andExpect(OpenApiMatchers.conformsTo(pets));
    }

    @Test
    void checksExchangesAgainstNamedOperations() throws Exception {
        var pets = runtimes.get("pets");
        mvc.perform(post("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\"}"))
            .andExpect(OpenApiMatchers.conformsTo(pets, "petAdopted"));
        assertThatThrownBy(() -> mvc.perform(post("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"x\"}"))
            .andExpect(OpenApiMatchers.conformsTo(pets, "petAdopted"))).isInstanceOf(AssertionError.class)
            .hasMessageContaining("request.body.schema.required").hasMessageContaining("response.body.schema.required");
    }

    @Test
    void rejectsViolations() {
        var pets = runtimes.get("pets");
        assertThatThrownBy(() -> mvc.perform(get("/api/broken")).andExpect(OpenApiMatchers.conformsTo(pets)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("GET /api/broken does not conform").hasMessageContaining("response.body.schema.required");
        assertThatThrownBy(() -> mvc.perform(get("/api/broken")).andExpect(OpenApiMatchers.responseConformsTo(pets)))
            .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> mvc.perform(post("/api/pets").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\"}"))
            .andExpect(OpenApiMatchers.conformsTo(pets))).isInstanceOf(AssertionError.class).hasMessageContaining("request.security.missing");
        assertThatThrownBy(() -> mvc.perform(get("/api/unknown")).andExpect(OpenApiMatchers.responseConformsTo(pets)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("request.path.missing");
        assertThat("x".getBytes(StandardCharsets.UTF_8)).hasSize(1);
    }
}
