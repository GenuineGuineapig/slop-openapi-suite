package com.example.petstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.petstore.api.CreatePetRequest;
import com.example.petstore.api.CreatePetResponse;
import com.example.petstore.api.DeletePetRequest;
import com.example.petstore.api.DeletePetResponse;
import com.example.petstore.api.GetPetRequest;
import com.example.petstore.api.GetPetResponse;
import com.example.petstore.api.ListPetsRequest;
import com.example.petstore.api.ListPetsResponse;
import com.example.petstore.api.PetsClient;
import com.example.petstore.model.Cat;
import com.example.petstore.model.Dog;
import com.example.petstore.model.Kind;
import com.example.petstore.model.NewPet;
import dev.openapifn.spring.client.OpenApiClients;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The generated client talks to the generated controllers over HTTP; both sides validate against the description. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "openapi-fn.clients.petstore.validate=true")
class PetstoreApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    OpenApiClients clients;

    @Test
    void roundTrip() {
        // The injectable PetsClient bean uses openapi-fn.clients.petstore.base-url; the random port is only known now.
        var client = new PetsClient(clients.http(PetstoreSpec.NAME, "http://localhost:" + port + "/api"));
        var created = client.createPet(new CreatePetRequest(new NewPet("Tom", Kind.CAT, Set.of("lazy"))));
        assertThat(created).isInstanceOfSatisfying(CreatePetResponse.Created.class, response -> {
            assertThat(response.body()).isEqualTo(new Cat("Tom", Kind.CAT, Set.of("lazy"), 1L, true));
            assertThat(response.location()).isEqualTo(URI.create("/api/pets/1"));
        });
        client.createPet(new CreatePetRequest(new NewPet("Rex", Kind.DOG, null)));
        assertThat(client.createPet(new CreatePetRequest(new NewPet("Rex", Kind.DOG, null)))).isInstanceOf(CreatePetResponse.Conflict.class);

        var dogs = client.listPets(new ListPetsRequest(Kind.DOG, null));
        assertThat(dogs).isEqualTo(ListPetsResponse.ok(java.util.List.of(new Dog("Rex", Kind.DOG, null, 2L, true)), 1));
        assertThat(client.getPet(new GetPetRequest(1L))).isInstanceOf(GetPetResponse.Ok.class);
        assertThat(client.deletePet(new DeletePetRequest(1L))).isEqualTo(DeletePetResponse.noContent());
        assertThat(client.getPet(new GetPetRequest(1L))).isInstanceOfSatisfying(GetPetResponse.NotFound.class,
            notFound -> assertThat(notFound.body().detail()).isEqualTo("No pet 1"));
    }

    @Test
    void invalidRequestsAreRejectedBeforeTheyReachTheService() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/pets"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"\", \"kind\": \"fish\"}"))
            .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.body()).contains("request.body.schema.minLength", "request.body.schema.enum");
    }
}
