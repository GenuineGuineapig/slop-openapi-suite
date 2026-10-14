package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openapifn.spring.app.EventsController;
import dev.openapifn.spring.app.TestApplication;
import dev.openapifn.spring.client.ClientCall;
import dev.openapifn.spring.client.OpenApiClientValidationInterceptor;
import dev.openapifn.spring.client.OpenApiHttpClient;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.model.ParameterLocation;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Bodies of sequential media types are streamed item by item, and validated as they pass. */
class StreamingTest {

    private static final ParameterSerializer.Spec FORMAT = ParameterSerializer.Spec.of("format", ParameterLocation.QUERY);
    private static final ParameterSerializer.Spec MODE = ParameterSerializer.Spec.of("mode", ParameterLocation.QUERY);

    @Nested
    @SpringBootTest(classes = TestApplication.class)
    @AutoConfigureMockMvc
    class Logging {

        @Autowired
        MockMvc mvc;

        @Test
        void streamsResponses() throws Exception {
            mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Stream", "yes"))
                .andExpect(content().contentType("application/jsonl"))
                .andExpect(content().string("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n"));
            mvc.perform(get("/api/events?format=sse&count=2"))
                .andExpect(content().contentType("text/event-stream"))
                .andExpect(content().string("event: tick\ndata: 0\n\nevent: tick\ndata: 1\n\n"));
            var mixed = mvc.perform(get("/api/events?format=mixed")).andExpect(status().isOk()).andReturn().getResponse();
            var boundary = org.springframework.http.MediaType.parseMediaType(mixed.getContentType()).getParameter("boundary");
            assertThat(mixed.getContentAsString()).isEqualTo("--" + boundary + "\r\nContent-Type: application/json\r\n\r\n{\"n\":0}\r\n--" + boundary
                + "\r\nContent-Type: image/png\r\n\r\nA\r\n--" + boundary + "--\r\n");
            mvc.perform(get("/api/events?format=json&count=2"))
                .andExpect(content().contentType("application/json"))
                .andExpect(content().json("[{\"n\":0},{\"n\":1}]"));
            // Logged, but passed on.
            mvc.perform(get("/api/events?invalid=1")).andExpect(content().string("{\"n\":0}\n{\"n\":-1}\n{\"n\":2}\n"));
            mvc.perform(get("/api/events?headless=true")).andExpect(status().isOk()).andExpect(content().string("{\"n\":0}\n{\"n\":1}\n{\"n\":2}\n"));
        }

        @Test
        void streamsRequests() throws Exception {
            mvc.perform(post("/api/events").contentType("application/jsonl").content("{\"n\":1}\n\n{\"n\":2}\n"))
                .andExpect(status().isOk()).andExpect(content().string("3"));
            mvc.perform(post("/api/events").contentType("application/x-ndjson").content("{\"n\":4}\n{\"n\":5}"))
                .andExpect(status().isOk()).andExpect(content().string("9"));
            mvc.perform(post("/api/events?mode=list").contentType("application/jsonl").content("{\"n\":1}\n{\"n\":6}\n"))
                .andExpect(content().string("7"));
            mvc.perform(post("/api/events").contentType("application/json").content("[{\"n\":8}]")).andExpect(content().string("8"));
            mvc.perform(post("/api/events?mode=list").contentType("application/json").content("[{\"n\":2}]")).andExpect(content().string("2"));
            mvc.perform(post("/api/events").contentType("application/jsonl").content("{\"n\":1}\n{\"n\":-1}\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].key").value("request.body.schema.minimum"))
                .andExpect(jsonPath("$.errors[0].location").value("body/1/n"));
            mvc.perform(post("/api/events").contentType("application/json-seq").content("\u001e{\"n\":1}\n"))
                .andExpect(status().isUnsupportedMediaType());
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = {"openapi-fn.validation.response.mode=FAIL", "openapi-fn.validation.max-body-size=20B"})
    @AutoConfigureMockMvc
    class Failing {

        @Autowired
        MockMvc mvc;

        @Test
        void invalidStreamsEndBeforeTheInvalidItem() throws Exception {
            // Nothing sent yet: the response becomes a problem.
            mvc.perform(get("/api/events?invalid=0"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errors[0].key").value("response.body.schema.minimum"))
                .andExpect(jsonPath("$.errors[0].location").value("body/0/n"));
            mvc.perform(get("/api/events?headless=true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errors[0].key").value("response.header.missing"));
            // The first item is out: the stream ends before the second.
            mvc.perform(get("/api/events?invalid=1")).andExpect(status().isOk()).andExpect(content().string("{\"n\":0}\n"));
            mvc.perform(get("/api/events?format=sse")).andExpect(status().isOk());
        }

        @Test
        void streamedRequestsAreValidatedWhateverTheirSize() throws Exception {
            mvc.perform(post("/api/events").contentType("application/jsonl").content("{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n{\"n\":-4}\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].location").value("body/3/n"));
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class OverHttp {

        @LocalServerPort
        int port;

        @Autowired
        OpenApiRuntimes runtimes;

        private OpenApiHttpClient client(RestClient.Builder builder) {
            return new OpenApiHttpClient(builder.build(), "http://localhost:" + port + "/api", JsonMapper.builder().build());
        }

        /** The server waits for the client to receive the first item before it produces the second. */
        @Test
        void responsesArriveItemByItem() {
            for (var builder : java.util.List.of(RestClient.builder(),
                RestClient.builder().requestInterceptor(new OpenApiClientValidationInterceptor(runtimes.get("pets").validator(), true)))) {
                EventsController.received = new CountDownLatch(1);
                var call = ClientCall.of("GET", "/events").param(FORMAT, "wait").accept("application/jsonl");
                var numbers = client(builder).<java.util.List<Integer>>exchange(call, response -> {
                    try (var items = response.items(new TypeReference<Map<String, Integer>>() { })) {
                        return items.map(item -> {
                            EventsController.received.countDown();
                            return item.get("n");
                        }).toList();
                    }
                });
                assertThat(numbers).containsExactly(0, 1);
            }
        }

        /** The client waits for the server to receive the first item before it produces the second. */
        @Test
        void requestsAreSentItemByItem() throws Exception {
            EventsController.received = new CountDownLatch(1);
            var latch = EventsController.received;
            var events = Stream.of(0, 1).map(i -> Map.of("n", i == 0 ? 0 : await(latch) ? 1 : 99));
            var call = ClientCall.of("POST", "/events").param(MODE, "wait").body("application/jsonl", events);
            assertThat(client(RestClient.builder()).<Integer>exchange(call, response -> response.body(Integer.class))).isEqualTo(1);
        }

        /** With validation, the client still streams: each item is validated as it is written. */
        @Test
        void validatingClientsStreamRequests() {
            var validator = new dev.openapifn.spring.client.ClientValidator(runtimes.get("pets").validator(), true);
            var client = new OpenApiHttpClient(RestClient.builder().defaultHeader("X-Key", "k").build(), "http://localhost:" + port + "/api",
                JsonMapper.builder().build(), validator);
            EventsController.received = new CountDownLatch(1);
            var latch = EventsController.received;
            var events = Stream.of(0, 1).map(i -> Map.of("n", i == 0 ? 0 : await(latch) ? 1 : 99));
            var call = ClientCall.of("POST", "/events").param(MODE, "wait").body("application/jsonl", events);
            assertThat(client.<Integer>exchange(call, response -> response.body(Integer.class))).isEqualTo(1);
            // The default header carries the credentials the description requires.
            var created = ClientCall.of("POST", "/pets").body("application/json", Map.of("name", "Rex"));
            assertThat(client.<Integer>exchange(created, dev.openapifn.spring.client.ClientResponse::status)).isEqualTo(201);
            var anonymous = new OpenApiHttpClient(RestClient.create(), "http://localhost:" + port + "/api", JsonMapper.builder().build(), validator);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> anonymous.exchange(created, dev.openapifn.spring.client.ClientResponse::status))
                .isInstanceOf(dev.openapifn.spring.client.ClientValidationException.class).hasMessageContaining("request.security.missing");
            var invalid = ClientCall.of("POST", "/events").body("application/jsonl", Stream.of(Map.of("n", 1), Map.of("n", -1)));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.exchange(invalid, dev.openapifn.spring.client.ClientResponse::status))
                .hasStackTraceContaining("request.body.schema.minimum");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.exchange(ClientCall.of("GET", "/broken"),
                    dev.openapifn.spring.client.ClientResponse::status))
                .isInstanceOf(dev.openapifn.spring.client.ClientValidationException.class).hasMessageContaining("response.body.schema.required");
            var badItems = ClientCall.of("GET", "/events").param(ParameterSerializer.Spec.of("invalid", ParameterLocation.QUERY), 1)
                .accept("application/jsonl");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.exchange(badItems, response -> {
                try (var items = response.items(new TypeReference<Map<String, Integer>>() { })) {
                    return items.toList();
                }
            })).isInstanceOf(dev.openapifn.spring.client.ClientValidationException.class).hasMessageContaining("response.body.schema.minimum");
            var mixed = ClientCall.of("GET", "/events").param(FORMAT, "mixed").accept("multipart/mixed");
            assertThat(client.<Integer>exchange(mixed, dev.openapifn.spring.client.ClientResponse::status)).isEqualTo(200);
        }

        private static boolean await(CountDownLatch latch) {
            try {
                return latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
