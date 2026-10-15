package dev.openapifn.spring;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openapifn.spring.app.TestApplication;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class FilterModesTest {

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = {
        "openapi-fn.validation.response.mode=FAIL",
        "openapi-fn.validation.reject-unknown-paths=true",
        "openapi-fn.validation.max-body-size=20B"})
    @AutoConfigureMockMvc
    class Strict {

        @Autowired
        MockMvc mvc;

        @Test
        void invalidResponsesBecomeServerErrors() throws Exception {
            mvc.perform(get("/api/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(Problems.MEDIA_TYPE))
                .andExpect(jsonPath("$.detail").value("Response validation failed"))
                .andExpect(jsonPath("$.errors[0].key").value("response.body.schema.required"));
            mvc.perform(get("/api/pets/1")).andExpect(status().isOk());
        }

        @Test
        void webhookReceiversAreValidatedLikePaths() throws Exception {
            mvc.perform(post("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rex"));
            mvc.perform(post("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).content("{\"tag\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].key").value("request.body.schema.required"));
            mvc.perform(post("/hooks/adopted").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"broken\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errors[0].key").value("response.body.schema.required"));
            mvc.perform(get("/hooks/adopted")).andExpect(status().isMethodNotAllowed());
        }

        @Test
        void unknownPathsAreRejected() throws Exception {
            mvc.perform(get("/api/undocumented"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].key").value("request.path.missing"));
        }

        @Test
        void largeBodiesSkipTheFilter() throws Exception {
            mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A long name here\"}"))
                .andExpect(status().isCreated());
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = {
        "openapi-fn.validation.request.enabled=false",
        "openapi-fn.validation.response.enabled=false",
        "openapi-fn.specs.pets.base-path=/mounted",
        "openapi-fn.validation.levels.request.parameter.query.schema=WARN",
        "openapi-fn.validation.assert-formats=false"})
    @AutoConfigureMockMvc
    class Lenient {

        @Autowired
        MockMvc mvc;

        @Test
        void invalidRequestsReachTheHandler() throws Exception {
            mvc.perform(get("/mounted/pets?limit=500")).andExpect(status().isOk());
            mvc.perform(get("/mounted/broken")).andExpect(status().isOk());
            mvc.perform(MockMvcRequestBuilders.put("/mounted/pets")).andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, properties = "openapi-fn.validation.request.enabled=false")
    @AutoConfigureMockMvc
    class ResponsesOnly {

        @Autowired
        MockMvc mvc;

        @Test
        void unmatchedMethodsPassWithoutResponseValidation() throws Exception {
            mvc.perform(MockMvcRequestBuilders.put("/api/pets")).andExpect(status().isMethodNotAllowed());
        }
    }
}
