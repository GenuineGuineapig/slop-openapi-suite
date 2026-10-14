package dev.openapifn.spring.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.spring.Problems;
import dev.openapifn.spring.webflux.app.PetsController;
import dev.openapifn.spring.webflux.app.ReactiveTestApplication;
import dev.openapifn.spring.webflux.app.Upload;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

/** The reactive runtime and validation filter on a running server. */
class ReactiveIntegrationTest {

    private static WebTestClient client(int port) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Nested
    @SpringBootTest(classes = ReactiveTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Logging {

        @LocalServerPort
        int port;

        WebTestClient client;

        @BeforeEach
        void setUp() {
            client = client(port);
            PetsController.RECEIVED.clear();
        }

        @Test
        void bindsParametersAndSerializesResponses() {
            client.get().uri("/api/pets?limit=3&tags=a,b").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Total", "3")
                .expectHeader().valueEquals("X-Ids", "1,2")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().json("[{\"id\":1,\"name\":\"Rex\"}]");
            assertThat(PetsController.RECEIVED).containsExactly(3, List.of("a", "b"));
            client.get().uri("/api/pets?limit=7").exchange().expectStatus().isOk().expectHeader().doesNotExist("X-Ids");
            client.get().uri("/api/pets").accept(MediaType.APPLICATION_XML).exchange()
                .expectHeader().contentType(MediaType.APPLICATION_XML)
                .expectBody(String.class).isEqualTo("<?xml version=\"1.0\" encoding=\"UTF-8\"?><pets><pets><id>1</id><name>Rex</name></pets></pets>");
            client.get().uri("/api/pets/1").exchange().expectStatus().isOk().expectBody().json("{\"id\":1,\"name\":\"Rex\"}");
            client.get().uri("/api/pets/2").exchange().expectHeader().contentType("text/plain").expectBody(String.class).isEqualTo("Rex");
            client.get().uri("/api/pets/404").exchange().expectStatus().isNotFound().expectBody().isEmpty();
            client.delete().uri("/api/pets/1").exchange().expectStatus().isNoContent();
            client.get().uri("/api/undocumented").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("free");
        }

        @Test
        void bindsBodies() {
            client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\",\"tag\":\"t\"}")
                .exchange().expectStatus().isCreated().expectBody().json("{\"id\":5,\"name\":\"Rex\",\"tag\":\"t\"}");
            client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_FORM_URLENCODED).bodyValue("name=Bello")
                .exchange().expectStatus().isCreated().expectBody().json("{\"id\":5,\"name\":\"Bello\"}");
            var parts = new MultipartBodyBuilder();
            parts.part("file", "PNG".getBytes(StandardCharsets.UTF_8)).filename("a.png").contentType(MediaType.IMAGE_PNG);
            parts.part("extra", "x".getBytes(StandardCharsets.UTF_8)).filename("x.bin").contentType(MediaType.APPLICATION_OCTET_STREAM);
            parts.part("caption", "hello");
            client.put().uri("/api/pets/.1/photo").body(BodyInserters.fromMultipartData(parts.build())).exchange()
                .expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_PNG).expectBody(String.class).isEqualTo("PNG");
            assertThat(PetsController.RECEIVED).singleElement().isInstanceOfSatisfying(Upload.class, upload -> {
                assertThat(upload.file().filename()).isEqualTo("a.png");
                assertThat(upload.extra()).singleElement().satisfies(extra -> assertThat(extra.text()).isEqualTo("x"));
                assertThat(upload.caption()).isEqualTo("hello");
            });
            client.put().uri("/api/pets/.1/photo").contentType(MediaType.IMAGE_PNG).bodyValue(new byte[] {1, 2}).exchange()
                .expectStatus().isOk().expectBody(byte[].class).isEqualTo(new byte[] {1, 2});
        }

        @Test
        void rejectsInvalidRequestsWithProblemDetails() {
            client.get().uri("/api/pets?limit=500").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType(Problems.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.errors[0].key").isEqualTo("request.parameter.query.schema.maximum")
                .jsonPath("$.errors[0].location").isEqualTo("query.limit");
            client.post().uri("/api/pets").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\"}").exchange()
                .expectStatus().isUnauthorized();
            client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.TEXT_PLAIN).bodyValue("a").exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            client.patch().uri("/api/pets").exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        void logsInvalidResponses() {
            client.get().uri("/api/broken").exchange().expectStatus().isOk().expectBody().json("{\"nope\":true}");
        }

        @Test
        void receivesWebhooks() {
            client.post().uri("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"Rex\"}").exchange()
                .expectStatus().isOk().expectBody().json("{\"name\":\"Rex\"}");
            client.get().uri("/hooks/adopted").exchange().expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        @Test
        void streamsResponses() {
            client.get().uri("/api/events").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Stream", "yes")
                .expectHeader().contentType("application/jsonl")
                .expectBody(String.class).isEqualTo("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n");
            client.get().uri("/api/events?format=stream&count=2").exchange().expectBody(String.class).isEqualTo("{\"n\":0}\n{\"n\":1}\n");
            client.get().uri("/api/events?format=sse&count=2").exchange()
                .expectHeader().contentType("text/event-stream")
                .expectBody(String.class).isEqualTo("event: tick\ndata: 0\n\nevent: tick\ndata: 1\n\n");
            var mixed = client.get().uri("/api/events?format=mixed").exchange().expectStatus().isOk().expectBody(String.class).returnResult();
            var boundary = mixed.getResponseHeaders().getContentType().getParameter("boundary");
            assertThat(mixed.getResponseBody()).isEqualTo("--" + boundary + "\r\nContent-Type: application/json\r\n\r\n{\"n\":0}\r\n--" + boundary
                + "\r\nContent-Type: image/png\r\n\r\nA\r\n--" + boundary + "--\r\n");
            client.get().uri("/api/events?format=json&count=2").exchange()
                .expectHeader().contentType(MediaType.APPLICATION_JSON).expectBody().json("[{\"n\":0},{\"n\":1}]");
            // Logged, but passed on.
            client.get().uri("/api/events?invalid=1").exchange().expectBody(String.class).isEqualTo("{\"n\":0}\n{\"n\":-1}\n{\"n\":2}\n");
            client.get().uri("/api/events?headless=true").exchange().expectStatus().isOk().expectBody(String.class)
                .isEqualTo("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n");
        }

        @Test
        void streamsRequests() {
            client.post().uri("/api/events").contentType(MediaType.parseMediaType("application/jsonl")).bodyValue("{\"n\":1}\n\n{\"n\":2}\n")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("3");
            client.post().uri("/api/events").contentType(MediaType.parseMediaType("application/x-ndjson")).bodyValue("{\"n\":4}\n{\"n\":5}")
                .exchange().expectBody(String.class).isEqualTo("9");
            client.post().uri("/api/events?mode=list").contentType(MediaType.APPLICATION_JSON).bodyValue("[{\"n\":1},{\"n\":6}]")
                .exchange().expectBody(String.class).isEqualTo("7");
            client.post().uri("/api/events").contentType(MediaType.APPLICATION_JSON).bodyValue("[{\"n\":8}]").exchange()
                .expectBody(String.class).isEqualTo("8");
            client.post().uri("/api/events").contentType(MediaType.parseMediaType("application/jsonl")).bodyValue("{\"n\":1}\n{\"n\":-1}\n")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors[0].key").isEqualTo("request.body.schema.minimum")
                .jsonPath("$.errors[0].location").isEqualTo("body/1/n");
            client.post().uri("/api/events").contentType(MediaType.parseMediaType("application/json-seq")).bodyValue("\u001e{\"n\":1}\n")
                .exchange().expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    @Nested
    @SpringBootTest(classes = ReactiveTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "openapi-fn.validation.response.mode=FAIL",
        "openapi-fn.validation.reject-unknown-paths=true",
        "openapi-fn.validation.max-body-size=20B",
        "openapi-fn.validation.exclude-paths=/api/undocumented"})
    class Failing {

        @LocalServerPort
        int port;

        WebTestClient client;

        @BeforeEach
        void setUp() {
            client = client(port);
        }

        @Test
        void invalidResponsesBecomeServerErrors() {
            client.get().uri("/api/broken").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectHeader().contentType(Problems.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Response validation failed")
                .jsonPath("$.errors[0].key").isEqualTo("response.body.schema.required");
            client.get().uri("/api/pets/1").exchange().expectStatus().isOk();
            client.delete().uri("/api/pets/1").exchange().expectStatus().isNoContent();
            client.post().uri("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"broken\"}").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        void invalidStreamsEndBeforeTheInvalidItem() {
            client.get().uri("/api/events?invalid=0").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.errors[0].key").isEqualTo("response.body.schema.minimum")
                .jsonPath("$.errors[0].location").isEqualTo("body/0/n");
            client.get().uri("/api/events?headless=true").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody().jsonPath("$.errors[0].key").isEqualTo("response.header.missing");
            client.get().uri("/api/events?invalid=1").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("{\"n\":0}\n");
            client.get().uri("/api/events?format=sse").exchange().expectStatus().isOk();
        }

        @Test
        void unknownAndExcludedPaths() {
            client.get().uri("/api/nowhere").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errors[0].key").isEqualTo("request.path.missing");
            client.get().uri("/api/undocumented").exchange().expectStatus().isOk();
        }

        @Test
        void largeBodiesSkipTheFilterUnlessStreamed() {
            client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"A long name here\"}")
                .exchange().expectStatus().isCreated();
            client.post().uri("/api/events").contentType(MediaType.parseMediaType("application/jsonl"))
                .bodyValue("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":-4}\n").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].location").isEqualTo("body/3/n");
        }
    }

    @Nested
    @SpringBootTest(classes = ReactiveTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "openapi-fn.validation.request.enabled=false",
        "openapi-fn.validation.response.enabled=false",
        "openapi-fn.specs.pets.base-path=/mounted"})
    class Lenient {

        @LocalServerPort
        int port;

        @Test
        void passesWhatIsInvalid() {
            var client = client(port);
            client.get().uri("/mounted/pets?limit=500").exchange().expectStatus().isOk().expectHeader().valueEquals("X-Total", "500");
            client.get().uri("/mounted/broken").exchange().expectStatus().isOk();
            client.post().uri("/mounted/events").contentType(MediaType.parseMediaType("application/jsonl")).bodyValue("{\"n\":-1}\n").exchange()
                .expectStatus().isOk().expectBody(String.class).isEqualTo("-1");
        }
    }
}
