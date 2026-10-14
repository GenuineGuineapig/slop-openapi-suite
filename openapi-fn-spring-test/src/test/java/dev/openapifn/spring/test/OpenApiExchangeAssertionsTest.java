package dev.openapifn.spring.test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.validation.OpenApiValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

class OpenApiExchangeAssertionsTest {

    private static final OpenApiRuntime PETS;

    static {
        var api = OpenApiLoader.defaults().load("classpath:META-INF/openapi-fn/pets/openapi.yaml").orElseThrow(d -> new AssertionError(d.toString()));
        PETS = new OpenApiRuntime("pets", api, OpenApiValidator.of(api), JsonMapper.builder().build(), true);
    }

    private final WebTestClient client = WebTestClient.bindToRouterFunction(RouterFunctions.route()
        .GET("/api/pets/{id}", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\"}"))
        .GET("/api/broken", request -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue("{}"))
        .POST("/api/pets", request -> request.bodyToMono(String.class).flatMap(body -> ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)))
        .DELETE("/api/pets/{id}", request -> ServerResponse.noContent().build())
        .POST("/hooks/adopted", request -> request.bodyToMono(String.class).flatMap(body -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)))
        .GET("/api/unknown", request -> ServerResponse.ok().build())
        .build()).build();

    @Test
    void acceptsConformingExchanges() {
        client.get().uri("/api/pets/1?").exchange().expectBody()
            .consumeWith(OpenApiExchangeAssertions.conformsTo(PETS))
            .consumeWith(OpenApiExchangeAssertions.responseConformsTo(PETS));
        client.delete().uri("/api/pets/1").exchange().expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS));
        client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\"}").exchange()
            .expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS));
        client.post().uri("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("name", "Rex")).exchange()
            .expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS, "petAdopted"));
    }

    @Test
    void rejectsViolations() {
        assertThatThrownBy(() -> client.get().uri("/api/broken").exchange().expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("GET /api/broken does not conform").hasMessageContaining("response.body.schema.required");
        assertThatThrownBy(() -> client.get().uri("/api/broken").exchange().expectBody().consumeWith(OpenApiExchangeAssertions.responseConformsTo(PETS)))
            .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> client.post().uri("/api/pets").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\"}").exchange()
            .expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS))).isInstanceOf(AssertionError.class)
            .hasMessageContaining("request.security.missing");
        assertThatThrownBy(() -> client.get().uri("/api/unknown").exchange().expectBody().consumeWith(OpenApiExchangeAssertions.responseConformsTo(PETS)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("request.path.missing");
        assertThatThrownBy(() -> client.post().uri("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"tag\":\"x\"}").exchange()
            .expectBody().consumeWith(OpenApiExchangeAssertions.conformsTo(PETS, "petAdopted"))).isInstanceOf(AssertionError.class)
            .hasMessageContaining("request.body.schema.required").hasMessageContaining("response.body.schema.required");
    }
}
