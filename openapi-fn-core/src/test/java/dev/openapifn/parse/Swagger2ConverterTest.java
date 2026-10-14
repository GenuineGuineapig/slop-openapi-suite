package dev.openapifn.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.json.Json;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.model.ParameterLocation;
import dev.openapifn.model.ParameterStyle;
import dev.openapifn.model.SecurityScheme;
import dev.openapifn.model.SpecVersion;
import dev.openapifn.validation.OpenApiValidator;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Swagger2ConverterTest {

    private static final OpenApiDocument API = OpenApiLoader.defaults().load("classpath:swagger2/petstore.yaml")
        .orElseThrow(diagnostics -> new AssertionError(diagnostics.toString()));

    private static JsonValue json(String text) {
        return Json.parse(text).orElseThrow(e -> new AssertionError(e.toString()));
    }

    private static OpenApiDocument parse(String yaml) {
        return new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/api.yaml"), yaml)
            .orElseThrow(diagnostics -> new AssertionError(diagnostics.toString()));
    }

    @Test
    void convertsTheDocument() {
        var model = API.model();
        assertThat(model.version()).isEqualTo(SpecVersion.V3_0);
        assertThat(model.info().title()).isEqualTo("Swagger Petstore");
        assertThat(model.servers()).extracting(server -> server.url()).containsExactly("https://petstore.example.com/v2", "http://petstore.example.com/v2");
        assertThat(model.extensions()).containsKey("x-root");
        assertThat(model.tags()).singleElement().satisfies(tag -> assertThat(tag.name()).isEqualTo("pets"));
        assertThat(model.externalDocs()).isPresent();
        assertThat(model.security()).singleElement().satisfies(requirement -> assertThat(requirement.schemes()).containsKey("apiKey"));
        assertThat(API.operations()).extracting(operation -> operation.displayName())
            .containsExactly("listPets", "createPet", "updatePet", "deletePet", "uploadPhoto", "getBatch", "addNote", "addLabels", "legacy");
        // Collection formats OpenAPI 3 cannot express are kept as an extension, not lost.
        assertThat(API.warnings()).isEmpty();
    }

    @Test
    void convertsParameters() {
        var list = API.operation("listPets").orElseThrow();
        assertThat(list.parameters()).extracting(p -> p.name() + ":" + p.style() + ":" + p.explode())
            .containsExactly("limit:FORM:true", "tags:PIPE_DELIMITED:false", "ids:FORM:true", "words:SPACE_DELIMITED:false",
                "tabs:FORM:false", "plain:FORM:false", "X-Trace:SIMPLE:false");
        var limit = list.parameters().getFirst();
        assertThat(limit.in()).isEqualTo(ParameterLocation.QUERY);
        assertThat(limit.example()).contains(json("10"));
        assertThat(API.schemas().deref(limit.schema().orElseThrow()).object().toJson()).isEqualTo("{\"type\":\"integer\",\"format\":\"int32\",\"maximum\":100}");
        var delete = API.operation("deletePet").orElseThrow();
        assertThat(delete.parameters()).extracting(p -> p.name() + ":" + p.in() + ":" + p.style())
            .containsExactly("petId:PATH:SIMPLE", "X-Tags:HEADER:SIMPLE", "X-Csv:HEADER:SIMPLE");
        assertThat(delete.parameters().getFirst().schema().orElseThrow().types()).containsExactly("string");
        assertThat(delete.parameters().getFirst().extensions()).containsKey("x-parameter");
        assertThat(delete.security()).isEmpty();
        assertThat(API.model().components().parameters()).containsOnlyKeys("limit", "tagsParam");
        assertThat(API.model().paths().items().get("/pets/{petId}").extensions()).containsKey("x-item");
        assertThat(ParameterStyle.values()).isNotEmpty();
    }

    @Test
    void convertsBodiesAndForms() {
        var create = API.operation("createPet").orElseThrow().operation().requestBody().orElseThrow();
        assertThat(create.required()).isTrue();
        assertThat(create.content()).containsOnlyKeys("application/json", "application/xml");
        assertThat(API.model().components().requestBodies()).containsOnlyKeys("petBody");
        var update = API.operation("updatePet").orElseThrow().operation().requestBody().orElseThrow();
        assertThat(update.required()).isFalse();
        assertThat(update.description()).contains("The pet");
        assertThat(update.content()).containsOnlyKeys("application/json");
        assertThat(API.operation("updatePet").orElseThrow().parameters()).extracting(p -> p.name() + ":" + p.in())
            .containsExactly("petId:PATH", "petId:QUERY");
        assertThat(API.operation("updatePet").orElseThrow().parameters().getLast().extensions()).doesNotContainKey("x-nullable");
        var photo = API.operation("uploadPhoto").orElseThrow().operation().requestBody().orElseThrow();
        assertThat(photo.required()).isTrue();
        var multipart = photo.content().get("multipart/form-data");
        assertThat(multipart.schema().orElseThrow().object().toJson()).isEqualTo("{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\","
            + "\"format\":\"binary\",\"description\":\"The photo\"},\"note\":{\"type\":\"string\"}},\"required\":[\"file\"]}");
        var note = API.operation("addNote").orElseThrow().operation().requestBody().orElseThrow().content().get("application/x-www-form-urlencoded");
        assertThat(note.encoding()).containsOnlyKeys("labels", "tabbed");
        assertThat(note.encoding().get("tabbed").extensions()).containsEntry("x-collection-format", json("\"tsv\""));
        assertThat(note.schema().orElseThrow().subschemaMap("properties")).containsOnlyKeys("author", "text", "labels", "many", "tabbed");
        assertThat(note.encoding().get("labels").style()).contains(ParameterStyle.PIPE_DELIMITED);
        var labels = API.operation("addLabels").orElseThrow().operation().requestBody().orElseThrow();
        assertThat(labels.required()).isFalse();
        assertThat(labels.content().get("multipart/form-data").encoding()).isEmpty();
    }

    @Test
    void convertsResponsesComponentsAndSchemas() {
        var list = API.operation("listPets").orElseThrow().operation().responses();
        var ok = list.forStatus(200).orElseThrow();
        assertThat(ok.content()).containsOnlyKeys("application/json", "application/xml");
        assertThat(ok.content().get("application/json").example()).contains(json("[{\"name\":\"Rex\",\"kind\":\"dog\"}]"));
        assertThat(ok.content().get("application/xml").example()).isEmpty();
        assertThat(ok.headers().get("X-Total").description()).contains("Total number of pets");
        assertThat(ok.extensions()).containsKey("x-response");
        assertThat(list.defaultResponse().orElseThrow().description()).contains("Not found");
        assertThat(API.operation("deletePet").orElseThrow().operation().responses().forStatus(204).orElseThrow().content()).isEmpty();
        var photo = API.operation("uploadPhoto").orElseThrow().operation().responses().forStatus(200).orElseThrow();
        assertThat(photo.content().get("image/png").schema().orElseThrow().format()).contains("binary");

        var pet = API.schemas().deref(API.model().components().schemas().get("Pet"));
        assertThat(pet.discriminator().orElseThrow().propertyName()).isEqualTo("kind");
        assertThat(pet.subschemaMap("properties").get("tag").nullable()).isTrue();
        assertThat(pet.subschemaMap("properties").get("owner").ref()).contains("#/components/schemas/Owner");
        assertThat(API.schemas().deref(pet.subschemaMap("properties").get("owner")).object().get("default")).contains(json("{\"$ref\":\"not-a-reference\"}"));
        assertThat(API.model().components().schemas().get("Error").object().get("x-error")).contains(json("{\"$ref\":\"kept\"}"));
        assertThat(API.model().components().schemas().get("Blob").format()).contains("binary");
        assertThat(API.model().components().schemas().get("Dog").subschemaList("allOf").getFirst().ref()).contains("#/components/schemas/Pet");

        var schemes = API.model().components().securitySchemes();
        assertThat(schemes.get("basicAuth")).isInstanceOfSatisfying(SecurityScheme.Http.class, http -> {
            assertThat(http.scheme()).isEqualTo("basic");
            assertThat(http.description()).contains("Basic authentication");
        });
        assertThat(schemes.get("apiKey")).isInstanceOf(SecurityScheme.ApiKey.class);
        assertThat(schemes.get("implicitAuth")).isInstanceOfSatisfying(SecurityScheme.OAuth2.class, oauth -> {
            assertThat(oauth.flows().implicit().orElseThrow().scopes()).containsKey("read");
        });
        assertThat(schemes.get("passwordAuth")).isInstanceOfSatisfying(SecurityScheme.OAuth2.class, oauth -> assertThat(oauth.flows().password()).isPresent());
        assertThat(schemes.get("appAuth")).isInstanceOfSatisfying(SecurityScheme.OAuth2.class, oauth -> assertThat(oauth.flows().clientCredentials()
            .orElseThrow().scopes()).isEmpty());
        assertThat(schemes.get("codeAuth")).isInstanceOfSatisfying(SecurityScheme.OAuth2.class, oauth -> {
            assertThat(oauth.flows().authorizationCode()).isPresent();
            assertThat(oauth.extensions()).containsKey("x-scheme");
        });
    }

    @Test
    void validatesAgainstTheConvertedDescription() {
        var validator = OpenApiValidator.of(API);
        var list = HttpRequest.of("GET", "/v2/pets?limit=5&tags=a|b&ids=1&ids=2").withHeader("api_key", "k");
        assertThat(validator.validateRequest(list).report().isEmpty()).isTrue();
        assertThat(validator.validateRequest(HttpRequest.of("GET", "/v2/pets?limit=500").withHeader("api_key", "k")).report().errors())
            .extracting(issue -> issue.key()).containsExactly("request.parameter.query.schema.maximum");
        var created = HttpRequest.of("POST", "/v2/pets").withHeader("api_key", "k").withBody("application/json", "{\"name\":\"Rex\",\"kind\":\"dog\"}");
        assertThat(validator.validateRequest(created).report().isEmpty()).isTrue();
        assertThat(validator.validateResponse(created, HttpResponse.of(201).withBody("application/json", "{\"name\":\"Rex\"}")).errors())
            .extracting(issue -> issue.key()).containsExactly("response.body.schema.required");
    }

    @Test
    void keepsCollectionFormatsOpenApi3CannotExpress() {
        var validator = OpenApiValidator.of(API);
        var list = validator.validateRequest(HttpRequest.of("GET", "/v2/pets?tabs=a%09b&words=c%20d&tags=e|f").withHeader("api_key", "k"));
        assertThat(list.report().isEmpty()).isTrue();
        assertThat(list.parameters()).containsEntry("query.tabs", json("[\"a\",\"b\"]")).containsEntry("query.words", json("[\"c\",\"d\"]"));
        var deleted = validator.validateRequest(HttpRequest.of("DELETE", "/v2/pets/1").withHeader("X-Tags", "a|b").withHeader("api_key", "k"));
        assertThat(deleted.parameters()).containsEntry("header.X-Tags", json("[\"a\",\"b\"]"));
        var batch = validator.validateRequest(HttpRequest.of("GET", "/v2/batches/1%092").withHeader("api_key", "k"));
        assertThat(batch.report().isEmpty()).isTrue();
        assertThat(batch.parameters()).containsEntry("path.ids", json("[1,2]"));
        var note = validator.validateRequest(HttpRequest.of("POST", "/v2/pets/1/notes").withHeader("api_key", "k")
            .withBody("application/x-www-form-urlencoded", "text=t&labels=a|b&tabbed=x%09y&many=1&many=2"));
        assertThat(note.report().isEmpty()).isTrue();
        assertThat(note.body()).contains(json("{\"text\":\"t\",\"labels\":[\"a\",\"b\"],\"tabbed\":[\"x\",\"y\"],\"many\":[\"1\",\"2\"]}"));
        var listed = HttpRequest.of("GET", "/v2/pets").withHeader("api_key", "k");
        assertThat(validator.validateResponse(listed, HttpResponse.of(200).withHeader("X-Codes", "1 2").withBody("application/json", "[]")).isEmpty())
            .isTrue();
        assertThat(validator.validateResponse(listed, HttpResponse.of(200).withHeader("X-Codes", "1 x").withBody("application/json", "[]")).errors())
            .extracting(issue -> issue.key()).containsExactly("response.header.schema.type");

        var tabs = API.operation("listPets").orElseThrow().parameters().stream().filter(p -> p.name().equals("tabs")).findFirst().orElseThrow();
        assertThat(dev.openapifn.http.ParameterSerializer.query(dev.openapifn.http.ParameterSerializer.Spec.of(tabs), json("[\"a\",\"b\"]")))
            .containsExactly(Map.entry("tabs", "a%09b"));
        var tags = API.operation("deletePet").orElseThrow().parameters().get(1);
        assertThat(dev.openapifn.http.ParameterSerializer.header(dev.openapifn.http.ParameterSerializer.Spec.of(tags), json("[\"a\",\"b\"]")))
            .isEqualTo("a|b");
        var ids = API.operation("getBatch").orElseThrow().parameters().getFirst();
        assertThat(dev.openapifn.http.ParameterSerializer.path(dev.openapifn.http.ParameterSerializer.Spec.of(ids), json("[1,2]"))).isEqualTo("1%092");
        var form = API.operation("addNote").orElseThrow().operation().requestBody().orElseThrow().content().get("application/x-www-form-urlencoded");
        assertThat(dev.openapifn.http.BodyWriter.form(json("{\"labels\":[\"a\",\"b\"],\"tabbed\":[\"x\",\"y\"],\"many\":[\"1\",\"2\"]}"),
            dev.openapifn.http.BodyWriter.formSpecs(form.encoding()))).isEqualTo("labels=a%7Cb&tabbed=x%09y&many=1&many=2");

        var unusual = parse("""
            swagger: '2.0'
            info: {title: t, version: v}
            paths:
              /a:
                get:
                  parameters:
                    - {name: X-A, in: header, type: array, items: {type: string}, collectionFormat: multi}
                    - {name: b, in: query, type: array, items: {type: string}, collectionFormat: semicolons}
                  responses: {'200': {description: ok}}
            """);
        assertThat(unusual.warnings()).extracting(Diagnostic::message).containsExactly(
            "collectionFormat 'multi' is only valid for query and formData parameters; using csv", "unknown collectionFormat 'semicolons'; using csv");
    }

    @Test
    void derivesServersFromHostAndBasePath() {
        assertThat(parse("{swagger: '2.0', info: {title: t, version: v}, paths: {}}").model().servers().getFirst().url()).isEqualTo("/");
        assertThat(parse("{swagger: '2.0', info: {title: t, version: v}, basePath: /v1, paths: {}}").model().servers().getFirst().url()).isEqualTo("/v1");
        assertThat(parse("{swagger: '2.0', info: {title: t, version: v}, host: h.example.com, paths: {}}").model().servers().getFirst().url())
            .isEqualTo("https://h.example.com");
        assertThat(Swagger2Converter.applies(json("{\"swagger\":\"1.2\"}"))).isFalse();
        assertThat(Swagger2Converter.applies(json("[]"))).isFalse();
    }
}
