package dev.openapifn.spring.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.spring.webflux.app.ReactiveTestApplication;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

/**
 * Credentials are verified by what Spring Security authenticated an exchange as. A web filter
 * stands in for Spring Security's: it authenticates {@code X-Test-User: name:AUTHORITY,...} in the
 * reactive security context.
 */
@SpringBootTest(classes = ReactiveTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "openapi-fn.security.spring-security=true")
@Import(ReactiveSpringSecurityTest.Authentication.class)
class ReactiveSpringSecurityTest {

    private static final String PET = "{\"name\": \"Rex\"}";

    @TestConfiguration(proxyBeanMethods = false)
    static class Authentication {

        @Bean
        @Order(-100)
        WebFilter testAuthentication() {
            return (exchange, chain) -> {
                var user = exchange.getRequest().getHeaders().getFirst("X-Test-User");
                if (user == null) {
                    return chain.filter(exchange);
                }
                var parts = user.split(":", 2);
                var authentication = UsernamePasswordAuthenticationToken.authenticated(parts[0], "n/a", AuthorityUtils.createAuthorityList(parts[1].split(",")));
                return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
            };
        }
    }

    @LocalServerPort
    int port;

    @Test
    void exchangesMustBeAuthenticatedWithTheRequiredRoles() {
        var client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).responseTimeout(Duration.ofSeconds(10)).build();
        client.post().uri("/api/pets").header("X-Key", "k").contentType(MediaType.APPLICATION_JSON).bodyValue(PET).exchange()
            .expectStatus().isUnauthorized()
            .expectBody().jsonPath("$.errors[0].message").isEqualTo("credentials for 'key' rejected: the request is not authenticated; requires key");
        client.post().uri("/api/pets").header("X-Key", "k").header("X-Test-User", "ann:ROLE_reader").contentType(MediaType.APPLICATION_JSON).bodyValue(PET)
            .exchange().expectStatus().isForbidden();
        client.post().uri("/api/pets").header("X-Key", "k").header("X-Test-User", "ann:ROLE_writer").contentType(MediaType.APPLICATION_JSON).bodyValue(PET)
            .exchange().expectStatus().isCreated();
    }

    @Test
    void clientCertificatesAreKnownWhereTlsEnds() throws Exception {
        X509Certificate certificate;
        try (var pem = ReactiveSpringSecurityTest.class.getResourceAsStream("/security/client.pem")) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        assertThat(ReactiveMessages.head(MockServerHttpRequest.get("/x").build()).clientCertificates()).isEmpty();
        assertThat(ReactiveMessages.head(MockServerHttpRequest.get("/x").sslInfo(ssl(null)).build()).clientCertificates()).contains(List.of());
        assertThat(ReactiveMessages.head(MockServerHttpRequest.get("/x").sslInfo(ssl(new X509Certificate[] {certificate})).build()).clientCertificates())
            .contains(List.of(certificate));
    }

    private static SslInfo ssl(X509Certificate @Nullable [] chain) {
        return new SslInfo() {
            @Override
            public @Nullable String getSessionId() {
                return null;
            }

            @Override
            public X509Certificate @Nullable [] getPeerCertificates() {
                return chain;
            }
        };
    }
}
