package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openapifn.spring.app.Calls;
import dev.openapifn.spring.app.Pet;
import dev.openapifn.spring.app.TestApplication;
import dev.openapifn.spring.app.Upload;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class SpringIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OpenApiRuntimes runtimes;

    @BeforeEach
    void reset() {
        Calls.RECEIVED.clear();
    }

    @Test
    void bindsParametersAndSerializesResponseHeaders() throws Exception {
        mvc.perform(get("/api/pets?limit=3&tags=a,b").header("X-Trace", "t-1").cookie(new Cookie("session", "s1")))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total", "3"))
            .andExpect(header().string("X-Ids", "1,2"))
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$[0].name").value("Rex"));
        assertThat(Calls.RECEIVED).containsExactly(3, List.of("a", "b"), "t-1", "s1");
        mvc.perform(get("/api/pets?limit=7")).andExpect(status().isOk()).andExpect(header().doesNotExist("X-Ids"));
        assertThat(runtimes.all()).containsOnlyKeys("pets", "remote");
    }

    @Test
    void negotiatesXml() throws Exception {
        mvc.perform(get("/api/pets").accept("application/xml"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/xml"))
            .andExpect(content().string("<?xml version=\"1.0\" encoding=\"UTF-8\"?><pets><pets><id>1</id><name>Rex</name></pets></pets>"));
    }

    @Test
    void rejectsInvalidRequestsWithProblemDetails() throws Exception {
        mvc.perform(get("/api/pets?limit=500"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(Problems.MEDIA_TYPE))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.errors[0].key").value("request.parameter.query.schema.maximum"))
            .andExpect(jsonPath("$.errors[0].location").value("query.limit"));
        mvc.perform(post("/api/pets").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/pets").header("X-Key", "k").contentType("text/csv").content("a"))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/pets").accept("text/html")).andExpect(status().isNotAcceptable());
        mvc.perform(put("/api/pets")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/pets/abc")).andExpect(status().isBadRequest());
        assertThat(Calls.RECEIVED).isEmpty();
    }

    @Test
    void bindsJsonAndFormBodies() throws Exception {
        mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\",\"tag\":\"dog\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(5));
        mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("name=Tom&tag=cat"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Tom"));
        assertThat(Calls.RECEIVED).containsExactly(new Pet(null, "Rex", "dog"), new Pet(null, "Tom", "cat"));
    }

    @Test
    void pathParametersStatusesAndTextResponses() throws Exception {
        mvc.perform(get("/api/pets/1")).andExpect(status().isOk()).andExpect(content().contentType("application/json"));
        mvc.perform(get("/api/pets/2")).andExpect(status().isOk()).andExpect(content().contentType("text/plain"));
        mvc.perform(get("/api/pets/1").accept("text/plain")).andExpect(status().isOk()).andExpect(content().contentType("text/plain"));
        mvc.perform(get("/api/pets/404")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/pets/9")).andExpect(status().isNoContent());
        assertThat(Calls.RECEIVED).containsExactly(1L, 2L, 1L, 404L, 9L);
    }

    @Test
    void bindsMultipartAndBinaryBodies() throws Exception {
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3});
        var extra1 = new MockMultipartFile("extra", "b.bin", "application/octet-stream", new byte[] {4});
        var extra2 = new MockMultipartFile("extra", "c.bin", "application/octet-stream", new byte[] {5});
        mvc.perform(multipart("/api/pets/.7/photo").file(file).file(extra1).file(extra2).part(new MockPart("caption", "hi".getBytes(StandardCharsets.UTF_8)))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(content().bytes(new byte[] {1, 2, 3}));
        assertThat(Calls.RECEIVED.getFirst()).isEqualTo(7);
        var upload = (Upload) Calls.RECEIVED.get(1);
        assertThat(upload.file()).isEqualTo(FilePart.of("a.png", "image/png", new byte[] {1, 2, 3}));
        assertThat(upload.extra()).extracting(FilePart::filename).containsExactly("b.bin", "c.bin");
        assertThat(upload.caption()).isEqualTo("hi");
        Calls.RECEIVED.clear();
        mvc.perform(put("/api/pets/.7/photo").contentType("image/png").content(new byte[] {9}))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/octet-stream"))
            .andExpect(content().bytes(new byte[] {9}));
        assertThat((byte[]) Calls.RECEIVED.get(1)).containsExactly(9);
        assertThat(((FilePart) Calls.RECEIVED.get(2)).contentType()).startsWith("image/png");
        Calls.RECEIVED.clear();
        mvc.perform(put("/api/pets/.7/photo").contentType("text/plain").content("hello"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/jsonl"))
            .andExpect(content().string("{\"text\":\"hello\"}\n"));
        assertThat(Calls.RECEIVED.get(1)).isEqualTo("hello");
    }

    @Test
    void undescribedPathsPassThroughAndInvalidResponsesAreLogged() throws Exception {
        mvc.perform(get("/api/undocumented")).andExpect(status().isOk()).andExpect(content().string("free"));
        mvc.perform(get("/api/broken")).andExpect(status().isOk()).andExpect(jsonPath("$.nope").value(true));
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void bodiesAreCachedForDownstreamReaders() throws Exception {
        var result = mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Rex\"}".getBytes(StandardCharsets.UTF_8))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(Arrays.asList(result.getRequest().getAttributeNames().asIterator().next())).isNotEmpty();
    }
}
