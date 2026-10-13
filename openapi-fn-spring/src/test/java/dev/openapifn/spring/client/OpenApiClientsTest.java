package dev.openapifn.spring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.openapifn.http.Headers;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.autoconfigure.OpenApiFnAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenApiClientsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenApiFnAutoConfiguration.class));

    @Test
    void createsClientsFromProperties() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        runner.withBean(RestClient.Builder.class, () -> builder)
            .withPropertyValues("openapi-fn.clients.pets.base-url=https://pets.test/api", "openapi-fn.clients.pets.validate=true")
            .run(context -> {
                var clients = context.getBean(OpenApiClients.class);
                var http = clients.http("pets", "https://default.test");
                assertThat(http.baseUrl()).isEqualTo("https://pets.test/api");
                server.expect(requestTo("https://pets.test/api/pets/1")).andRespond(withSuccess("{\"name\":1}", MediaType.APPLICATION_JSON));
                var call = ClientCall.of("GET", "/pets/1");
                assertThatThrownBy(() -> http.exchange(call, response -> null)).isInstanceOf(OpenApiValidationException.class);
                assertThat(clients.http("other", "https://default.test").baseUrl()).isEqualTo("https://default.test");
                assertThat(context.getBean(OpenApiRuntimes.class).all()).containsKey("pets");
            });
        runner.withPropertyValues("openapi-fn.clients.pets.validate=false")
            .run(context -> assertThat(context.getBean(OpenApiClients.class).http("pets", "https://d.test").baseUrl()).isEqualTo("https://d.test"));
    }

    @Test
    void unexpectedResponses() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://h.test/x")).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(418)));
        var http = new OpenApiHttpClient(builder.build(), "https://h.test", JsonMapper.builder().build());
        assertThatThrownBy(() -> http.exchange(ClientCall.of("GET", "/x"), response -> {
            throw new UnexpectedResponseException(response);
        })).isInstanceOfSatisfying(UnexpectedResponseException.class, e -> {
            assertThat(e.response().status()).isEqualTo(418);
            assertThat(e).hasMessage("Undescribed response status 418");
        });
        assertThat(new UnexpectedResponseException(new ClientResponse(500, Headers.EMPTY, new byte[0], JsonMapper.builder().build())).response().status())
            .isEqualTo(500);
    }
}
