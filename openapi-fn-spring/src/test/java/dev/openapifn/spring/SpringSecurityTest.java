package dev.openapifn.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.openapifn.fn.Result;
import dev.openapifn.security.Credential;
import dev.openapifn.security.CredentialVerifier;
import dev.openapifn.security.Grant;
import dev.openapifn.spring.app.TestApplication;
import jakarta.servlet.Filter;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Credentials are verified by what Spring Security authenticated a request as. A filter stands in
 * for Spring Security's: it authenticates {@code X-Test-User: name:AUTHORITY,...}.
 */
@SpringBootTest(classes = TestApplication.class, properties = "openapi-fn.security.spring-security=true")
@AutoConfigureMockMvc
@Import(SpringSecurityTest.Authentication.class)
class SpringSecurityTest {

    private static final String PET = "{\"name\": \"Rex\"}";

    @TestConfiguration(proxyBeanMethods = false)
    static class Authentication {

        @Bean
        FilterRegistrationBean<Filter> testAuthentication() {
            Filter filter = (request, response, chain) -> {
                var user = ((jakarta.servlet.http.HttpServletRequest) request).getHeader("X-Test-User");
                if (user != null) {
                    var parts = user.split(":", 2);
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(parts[0], "n/a",
                        AuthorityUtils.createAuthorityList(Arrays.copyOfRange(parts[1].split(","), 0, parts[1].split(",").length))));
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(-100);
            return registration;
        }

        /** The application's own verifier comes first; it only verifies one special key and declines the others. */
        @Bean
        CredentialVerifier masterKey() {
            return credential -> credential instanceof Credential.ApiKey key && key.value().equals("master")
                ? Optional.of(Result.ok(Grant.of("master", "writer"))) : Optional.empty();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void requestsMustBeAuthenticatedWithTheRequiredRoles() throws Exception {
        mvc.perform(post("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).content(PET))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errors[0].message").value("credentials for 'key' rejected: the request is not authenticated; requires key"));
        mvc.perform(post("/api/pets").header("X-Key", "k").header("X-Test-User", "ann:ROLE_reader").contentType(MediaType.APPLICATION_JSON).content(PET))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/pets").header("X-Key", "k").header("X-Test-User", "ann:ROLE_writer").contentType(MediaType.APPLICATION_JSON).content(PET))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/pets").header("X-Key", "master").contentType(MediaType.APPLICATION_JSON).content(PET))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/pets").header("X-Test-User", "ann:ROLE_writer").contentType(MediaType.APPLICATION_JSON).content(PET))
            .andExpect(status().isUnauthorized());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
