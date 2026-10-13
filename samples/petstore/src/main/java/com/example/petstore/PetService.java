package com.example.petstore;

import com.example.petstore.api.CreatePetRequest;
import com.example.petstore.api.CreatePetResponse;
import com.example.petstore.api.DeletePetRequest;
import com.example.petstore.api.DeletePetResponse;
import com.example.petstore.api.GetPetRequest;
import com.example.petstore.api.GetPetResponse;
import com.example.petstore.api.ListPetsRequest;
import com.example.petstore.api.ListPetsResponse;
import com.example.petstore.api.PetsApi;
import com.example.petstore.model.Cat;
import com.example.petstore.model.Dog;
import com.example.petstore.model.Kind;
import com.example.petstore.model.Pet;
import com.example.petstore.model.Problem;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * The hand-written part: business logic behind the generated {@link PetsApi}. Requests arrive
 * bound and validated; the returned responses are checked against the API description.
 */
@Service
public class PetService implements PetsApi {

    private final Map<Long, Pet> pets = new ConcurrentSkipListMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public ListPetsResponse listPets(ListPetsRequest request) {
        var matching = pets.values().stream().filter(pet -> request.kind() == null || kind(pet) == request.kind()).toList();
        return ListPetsResponse.ok(matching.stream().limit(request.limit()).toList(), matching.size());
    }

    @Override
    public CreatePetResponse createPet(CreatePetRequest request) {
        var pet = request.body();
        if (pets.values().stream().anyMatch(existing -> name(existing).equals(pet.name()))) {
            return CreatePetResponse.conflict(new Problem(null, "Conflict", 409, "A pet named '" + pet.name() + "' exists"));
        }
        var id = ids.incrementAndGet();
        Pet created = switch (pet.kind()) {
            case CAT -> new Cat(pet.name(), pet.kind(), pet.tags(), id, null);
            case DOG -> new Dog(pet.name(), pet.kind(), pet.tags(), id, true);
        };
        pets.put(id, created);
        return CreatePetResponse.created(created, URI.create("/api/pets/" + id));
    }

    @Override
    public GetPetResponse getPet(GetPetRequest request) {
        var pet = pets.get(request.petId());
        return pet != null ? GetPetResponse.ok(pet) : GetPetResponse.notFound(notFound(request.petId()));
    }

    @Override
    public DeletePetResponse deletePet(DeletePetRequest request) {
        return pets.remove(request.petId()) != null ? DeletePetResponse.noContent() : DeletePetResponse.notFound(notFound(request.petId()));
    }

    private static Problem notFound(long id) {
        return new Problem(null, "Not Found", 404, "No pet " + id);
    }

    private static Kind kind(Pet pet) {
        return switch (pet) {
            case Cat cat -> cat.kind();
            case Dog dog -> dog.kind();
        };
    }

    private static String name(Pet pet) {
        return switch (pet) {
            case Cat cat -> cat.name();
            case Dog dog -> dog.name();
        };
    }
}
