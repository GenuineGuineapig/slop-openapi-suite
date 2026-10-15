package com.example.galaxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.galaxy.api.AuthenticationClient;
import com.example.galaxy.api.CelestialBodiesClient;
import com.example.galaxy.api.CreateCelestialBodyRequest;
import com.example.galaxy.api.CreateCelestialBodyResponse;
import com.example.galaxy.api.CreatePlanetRequest;
import com.example.galaxy.api.CreatePlanetResponse;
import com.example.galaxy.api.CreateUserRequest;
import com.example.galaxy.api.CreateUserResponse;
import com.example.galaxy.api.DeletePlanetRequest;
import com.example.galaxy.api.DeletePlanetResponse;
import com.example.galaxy.api.GetAllDataRequest;
import com.example.galaxy.api.GetAllDataResponse;
import com.example.galaxy.api.GetMeResponse;
import com.example.galaxy.api.GetPlanetRequest;
import com.example.galaxy.api.GetPlanetResponse;
import com.example.galaxy.api.GetTokenRequest;
import com.example.galaxy.api.GetTokenResponse;
import com.example.galaxy.api.PlanetsClient;
import com.example.galaxy.api.UpdatePlanetRequest;
import com.example.galaxy.api.UpdatePlanetResponse;
import com.example.galaxy.api.UploadImageRequest;
import com.example.galaxy.api.UploadImageResponse;
import com.example.galaxy.mock.Galaxy;
import com.example.galaxy.mock.PlanetNotifier;
import com.example.galaxy.mock.Receivers;
import com.example.galaxy.model.Credentials;
import com.example.galaxy.model.Planet;
import com.example.galaxy.model.Satellite;
import dev.openapifn.spring.FilePart;
import dev.openapifn.spring.client.OpenApiClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The generated clients call the generated controllers over HTTP and the mocks in
 * {@code com.example.galaxy.mock} answer; callbacks and webhooks are sent by the application to
 * itself. Requests and responses are validated on both sides.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "openapi-fn.clients.galaxy.validate=true")
@Import(GalaxyApiIntegrationTest.ApiKey.class)
class GalaxyApiIntegrationTest {

    /** The API accepts several kinds of credentials; the generated clients send an API key header. */
    @TestConfiguration
    static class ApiKey {

        @Bean
        RestClientCustomizer apiKey() {
            return builder -> builder.defaultHeader("X-API-Key", "galaxy-key");
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    OpenApiClients clients;

    @Autowired
    PlanetNotifier notifier;

    @Autowired
    Receivers receivers;

    PlanetsClient planets;
    CelestialBodiesClient bodies;
    AuthenticationClient authentication;

    @BeforeEach
    void clients() {
        var http = clients.http(GalaxySpec.NAME, "http://localhost:" + port);
        planets = new PlanetsClient(http);
        bodies = new CelestialBodiesClient(http);
        authentication = new AuthenticationClient(http);
    }

    private URI local(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void pagesOfPlanetsAreTyped() {
        var request = new GetAllDataRequest(2L, 2L);
        var page = planets.getAllData(request);
        assertThat(page).isInstanceOfSatisfying(GetAllDataResponse.Ok.class, ok -> {
            // PaginatedResource<Planet>: a generic class generated from the description's $dynamicRef template.
            List<Planet> data = ok.body().data();
            assertThat(data.getFirst()).isEqualTo(Galaxy.JUPITER);
            assertThat(ok.xPaginationTotal()).isGreaterThanOrEqualTo(3);

            // The links of the response lead to the first planet on it, and back to the first page.
            var firstPlanet = planets.getFirstPlanet(request, ok);
            assertThat(firstPlanet.planetId()).isEqualTo(data.getFirst().id());
            var firstPage = planets.firstPage(request, ok);
            assertThat(firstPage).isEqualTo(new GetAllDataRequest(2L, 0L));
            assertThat(planets.getAllData(firstPage)).isInstanceOfSatisfying(GetAllDataResponse.Ok.class, first -> {
                assertThat(first.body().data()).containsExactly(Galaxy.EARTH, Galaxy.MARS);
                assertThat(first.body().meta().next()).isEqualTo("/planets?limit=2&offset=2");
            });
        });
    }

    @Test
    void creatingAPlanetCallsBackAndNotifiesSubscribers() {
        notifier.subscribe(local("/webhooks/postNewPlanet"));
        var draft = Planet.builder().name("Kepler-22b").type(Planet.Type.SUPER_EARTH).habitabilityIndex(0.7f)
            .successCallbackUrl(local("/callbacks/planetCreated")).build();
        var request = new CreatePlanetRequest(draft);
        var created = (CreatePlanetResponse.Created) planets.createPlanet(request);
        var planet = created.body();
        assertThat(planet.name()).isEqualTo("Kepler-22b");
        assertThat(created.location()).isEqualTo(URI.create("https://galaxy.scalar.com/planets/" + planet.id()));
        assertThat(receivers.received()).contains("created:Kepler-22b", "webhook:Kepler-22b");

        // The links of the 201 response: the new planet's id (and, for an update, its body) taken from the response.
        assertThat(planets.getPlanet(planets.getPlanet(request, created))).isInstanceOfSatisfying(GetPlanetResponse.Ok.class,
            ok -> assertThat(ok.body().name()).isEqualTo("Kepler-22b"));
        var update = planets.updatePlanet(request, created);
        assertThat(update.planetId()).isEqualTo(planet.id());
        assertThat(update.body().name()).isEqualTo("Kepler-22b");

        var renamed = planets.updatePlanet(new UpdatePlanetRequest(planet.id(), draft.withName("Kepler-22c")));
        assertThat(renamed).isInstanceOfSatisfying(UpdatePlanetResponse.Ok.class, ok -> assertThat(ok.body().name()).isEqualTo("Kepler-22c"));
        assertThat(planets.getPlanet(new GetPlanetRequest(planet.id()))).isInstanceOfSatisfying(GetPlanetResponse.Ok.class,
            ok -> assertThat(ok.eTag()).isEqualTo("\"planet-" + planet.id() + "\""));

        var image = planets.uploadImage(new UploadImageRequest(planet.id(), new UploadImageRequest.Body(FilePart.of("kepler.png", "image/png",
            new byte[] {(byte) 0x89, 'P', 'N', 'G'}))));
        assertThat(image).isInstanceOfSatisfying(UploadImageResponse.Ok.class, ok -> {
            assertThat(ok.body().fileSize()).isEqualTo(4);
            assertThat(ok.body().mimeType()).isEqualTo("image/png");
        });

        // planetExploded is sent to the successCallbackUrl too, with the same method and a planet like planetCreated: the
        // description does not tell them apart, so the receiver of planetCreated gets it (the generator warns about it).
        assertThat(planets.deletePlanet(new DeletePlanetRequest(planet.id()))).isEqualTo(DeletePlanetResponse.noContent());
        assertThat(receivers.received()).contains("created:Kepler-22c");
        assertThat(planets.getPlanet(new GetPlanetRequest(planet.id()))).isInstanceOf(GetPlanetResponse.NotFound.class);
    }

    @Test
    void failedCreationsCallTheFailureUrl() {
        var nameless = Planet.builder().name(" ").failureCallbackUrl(local("/callbacks/planetCreationFailed")).build();
        assertThat(planets.createPlanet(new CreatePlanetRequest(nameless))).isInstanceOfSatisfying(CreatePlanetResponse.BadRequest.class,
            badRequest -> assertThat(badRequest.body().detail()).isEqualTo("A planet needs a name"));
        assertThat(receivers.received()).contains("failed: ");
    }

    @Test
    void celestialBodiesArePlanetsOrSatellites() {
        var moon = Satellite.builder().name("Europa").type(Satellite.Type.MOON).diameter(3121.6f).build();
        assertThat(bodies.createCelestialBody(new CreateCelestialBodyRequest(moon))).isInstanceOfSatisfying(CreateCelestialBodyResponse.Created.class,
            created -> assertThat(created.body()).isInstanceOfSatisfying(Satellite.class, satellite -> {
                assertThat(satellite.name()).isEqualTo("Europa");
                assertThat(satellite.id()).isNotNull();
            }));
    }

    @Test
    void signUpAndSignIn() {
        var signedUp = authentication.createUser(new CreateUserRequest(new CreateUserRequest.Body(null, "Ada", "ada@example.com", "engine")));
        assertThat(signedUp).isInstanceOfSatisfying(CreateUserResponse.Created.class, created -> assertThat(created.body().name()).isEqualTo("Ada"));
        assertThat(authentication.createUser(new CreateUserRequest(new CreateUserRequest.Body(null, "Ada", "ada@example.com", "engine"))))
            .isInstanceOf(CreateUserResponse.Conflict.class);
        assertThat(authentication.getToken(new GetTokenRequest(new Credentials("ada@example.com", "engine")))).isInstanceOfSatisfying(
            GetTokenResponse.Created.class, created -> assertThat(created.body().token()).startsWith("token-"));
        assertThat(authentication.getToken(new GetTokenRequest(new Credentials("ada@example.com", "wrong")))).isInstanceOf(GetTokenResponse.Unauthorized.class);
        assertThat(authentication.getMe()).isInstanceOfSatisfying(GetMeResponse.Ok.class, ok -> assertThat(ok.body().name()).isEqualTo("Marc"));
    }

    @Test
    void requestsThatBreakTheContractAreRejected() throws Exception {
        var http = HttpClient.newHttpClient();
        var anonymous = http.send(HttpRequest.newBuilder(local("/me")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var invalid = http.send(HttpRequest.newBuilder(local("/planets/mars")).header("X-API-Key", "galaxy-key").build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("request.parameter.path");
    }
}
