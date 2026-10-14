# openapi-fn

Give it an OpenAPI description and get a working Spring Boot 4 API out of it:

* **Generated**: models, controllers, delegate interfaces, HTTP clients, controller tests,
  receivers and senders for webhooks and callbacks.
* **Runtime**: request and response validation in the filter chain.

It is written in Java 21 in a functional style: immutable records, sealed types, and a `Result`
type instead of exceptions in the core.

It replaces two tools:

* **openapi-generator**, for Spring server and client code
* **Atlassian's swagger-request-validator**, for request and response validation, including in
  tests

It reads OpenAPI **3.0, 3.1 and 3.2** (and **Swagger / OpenAPI 2.0**, converted on load). A
description can span several documents, local or remote, linked with `$ref`. Schemas use the full
JSON Schema vocabulary, verified against the official JSON Schema test suite. See
[docs/spec-coverage.md](docs/spec-coverage.md) for coverage of the specification, feature by feature.

## Quick start

```kotlin
// build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("dev.openapifn") version "0.1.0-SNAPSHOT"
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

openapiFn {
    specs {
        register("petstore") {
            spec = file("src/main/openapi/petstore.yaml")   // documents it references are found relative to it
            packageName = "com.example.petstore"            // models in .model, API types in .api
        }
    }
}
```

Until the artifacts are published, use this repository as a composite build, as the samples do
(`pluginManagement { includeBuild("…") }` and `includeBuild("…")` in `settings.gradle.kts`).

The build generates everything into `build/generated/openapi-fn/petstore/` and compiles it with
your code. The plugin adds the runtime (`openapi-fn-spring`) and the test support
(`openapi-fn-spring-test`, `spring-boot-starter-webmvc-test`). The Gradle daemon needs Java 21;
add `gradle/gradle-daemon-jvm.properties` with `toolchainVersion=21`.

Implement the generated interface. That is all the server code you write:

```java
@Component
class PetService implements PetsApi {

    @Override
    public GetPetResponse getPet(GetPetRequest request) {
        return pets.find(request.petId())
            .<GetPetResponse>map(GetPetResponse::ok)
            .orElseGet(() -> GetPetResponse.notFound(new Problem("Not Found", 404)));
    }
}
```

Requests arrive bound to typed records and already validated. Every described response, error
responses included, is a variant of a sealed type, so the compiler checks you return something
the description allows. Operations without an implementation answer `501`.

Call an API through the generated client:

```java
var pets = new PetsClient(clients.http(PetstoreSpec.NAME, "https://pets.example.com"));
switch (pets.getPet(new GetPetRequest(42L))) {
    case GetPetResponse.Ok ok -> show(ok.body());
    case GetPetResponse.NotFound notFound -> warn(notFound.body().detail());
}
```

Working examples are in [`samples/`](samples): a petstore and three public APIs (Train Travel,
Scalar Galaxy, Redocly Museum). Each has mock implementations and integration tests that call the
running application through the generated clients.

## What gets generated

| Description | Java |
|---|---|
| Object schemas | Immutable records: null checks for required properties, immutable collections, defaults (also for `date`, `uuid`, `uri` …), `const` values, builders and `with…` copies |
| `enum` | Enums (string or integer) whose constants carry the JSON value. `x-enum-varnames` and `x-enum-descriptions` are honoured |
| `oneOf` / `anyOf` | Sealed interfaces. With a discriminator, Jackson type information (including 3.2 `defaultMapping`); without one, a strict deserializer that tries each member. Primitive members are wrapped |
| `allOf` with a discriminator | A sealed parent interface with shared accessors, its subtypes, and a `Default` record for unknown values |
| `$dynamicRef` templates | Generic records: `PaginatedResource<T>`, used as `PaginatedResource<Planet>` where a description binds the dynamic anchor |
| Operations | A request record per operation and a sealed response type with one record per status (or range, or `default`), including typed response headers |
| Paths | A delegate interface (`PetsApi`) and a `@RestController` per tag. Custom methods (3.2 `additionalOperations`, `QUERY`) are dispatched too |
| Webhooks, callbacks | Receiver interfaces and controllers (at `openapi-fn.specs.<api>.receivers.<method>.path`, default `/webhooks/<method>` or `/callbacks/<method>`), and senders that take the target URL |
| Clients | `RestClient` clients per tag that serialize every parameter style and body type, and decode every described response |
| Tests | A `@WebMvcTest` per controller: an exchange built from examples (validated against the description), plus a test that incomplete requests are rejected |
| Bundle | The description (with remote documents) under `META-INF/openapi-fn/<api>/`, found by the runtime automatically |

Request and response bodies cover these media types:

* JSON, XML (attributes, wrapping, namespaces, `nodeType`), forms and multipart (with encodings)
* text and binary (`FilePart`)
* sequential types (`application/jsonl`, `application/x-ndjson`, `application/json-seq`,
  `text/event-stream`, positional `multipart/mixed`)

## Validation at runtime

The auto-configured `OpenApiValidationFilter` validates every request routed to a described
operation (or webhook/callback receiver):

* parameters in every style
* request bodies of every media type, against JSON Schema
* security requirements (credentials present)
* the `Accept` header
* the response, as it leaves the application

Invalid requests get an RFC 9457 problem:

```json
{"type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Request validation failed", "instance": "/pets",
 "errors": [{"key": "request.body.schema.required", "level": "ERROR", "message": "missing required property 'name'", "location": "body"}]}
```

Configuration (all optional):

```yaml
openapi-fn:
  specs:
    petstore:
      base-path: /api                 # mount the API somewhere else than its server URL path
      location: classpath:other.yaml  # or register a description that was not generated
      receivers.newPet.path: /hooks/pets
  validation:
    request.enabled: true             # reject invalid requests (400/401/404/405/406/415)
    response.enabled: true
    response.mode: LOG                # or FAIL: replace invalid responses with a 500 problem
    reject-unknown-paths: false       # 404 for paths no description covers
    exclude-paths: [/actuator/**, /error]
    assert-formats: true
    max-body-size: 10MB               # larger bodies are passed through unvalidated
    levels:                           # issue key prefixes to ERROR, WARN or IGNORE
      request.parameter.query.unknown: IGNORE
  clients:
    petstore:
      base-url: https://pets.example.com   # default: the first server URL
      validate: true                        # validate what generated clients send and receive
```

Issue keys are stable and hierarchical, for example `request.body.schema.required`,
`response.header.missing` or `request.security.missing`. `levels` downgrades or ignores them by
prefix.

## Without Spring, and without generated code

The core (`openapi-fn-core`) depends only on snakeyaml-engine:

```java
var api = OpenApiLoader.defaults().load("classpath:petstore.yaml")
    .orElseThrow(diagnostics -> new IllegalStateException(diagnostics.toString()));
var validator = OpenApiValidator.of(api);

var request = HttpRequest.of("POST", "/v1/pets").withHeader("X-Api-Key", "k").withBody("application/json", "{\"name\": \"Rex\"}");
var validation = validator.validateRequest(request);       // routes, then validates
var report = validator.validateResponse(request, HttpResponse.of(201).withBody("application/json", "{\"id\": 1}"));
report.errors().forEach(System.out::println);
```

`OpenApiLoader` can also load remote documents (`ResourceLoaders.withRemote(...)`) and parse
descriptions from strings. Other entry points:

* `SchemaValidator`: JSON Schema on its own (drafts 4 to 2020-12 and the OAS 3.0 dialect)
* `ExampleGenerator`: valid example values
* `BodyParser` / `BodyWriter`: every supported media type

## Code generation without Gradle

```bash
java -jar openapi-fn-codegen-0.1.0-SNAPSHOT-cli.jar --spec api.yaml --name petstore --package com.example.petstore --output build/generated/sources
```

The self-contained jar comes from `./gradlew :openapi-fn-codegen:cliJar`. Options:
`--test-output`, `--resources-output`, `--base-path`, `--no-server`, `--no-client`, `--no-tests`. The Gradle task takes the same settings: `basePath`, `server`, `client` and `tests`
on each spec.

## Migrating from openapi-generator (spring generator)

| openapi-generator | openapi-fn |
|---|---|
| `delegatePattern` / `interfaceOnly` with annotated interfaces | Generated `@RestController` plus a plain `XxxApi` interface you implement as a bean. You write no annotations |
| `ResponseEntity<Pet>` return types | Sealed `XxxResponse` types with a record per described status and typed headers |
| Model POJOs with `useBeanValidation` | Immutable records. The description itself is enforced at runtime, so Bean Validation annotations are not needed |
| `oneOf` interfaces (`useOneOfInterfaces`) | Sealed interfaces with Jackson subtype information or a strict union deserializer |
| `x-enum-varnames` | Supported, as is `x-enum-descriptions` |
| `library: restclient` client | Generated clients on `RestClient`, configured under `openapi-fn.clients.<api>` |
| `apiPackage` / `modelPackage` | `packageName`: `.api` and `.model` below it |
| Swagger 2.0 input | Converted to 3.0 on load |

The generated code only depends on `openapi-fn-spring`. Regenerating never touches your code,
because you implement interfaces rather than edit generated files.

## Migrating from swagger-request-validator (Atlassian)

| swagger-request-validator | openapi-fn |
|---|---|
| `OpenApiInteractionValidator.createForSpecificationUrl(...)` | `OpenApiValidator.of(OpenApiLoader.defaults().load(...))` |
| `validator.validateRequest(SimpleRequest)` | `validator.validateRequest(HttpRequest)` |
| `validator.validate(request, response)` | `validator.validate(request, response)` (the request routes, the response is checked against the matched operation) |
| `ValidationReport`, message keys such as `validation.request.body.schema.required` | `ValidationReport`, keys such as `request.body.schema.required` |
| `LevelResolver` | `LevelPolicy` (`openapi-fn.validation.levels` in Spring) |
| Spring MVC `OpenApiValidationFilter` + interceptor | Auto-configured `OpenApiValidationFilter` (requests and responses, problem details) |
| `OpenApiValidationMatchers.openApi().isValid(...)` (MockMvc) | `OpenApiMatchers.conformsTo(runtime)` and `responseConformsTo(runtime)` |
| `OpenApiValidationClientHttpRequestInterceptor` | Generated clients with `openapi-fn.clients.<api>.validate=true`, or `OpenApiClientValidationInterceptor` on any `RestClient` |
| Swagger 2.0 and OpenAPI 3.0 | Swagger 2.0, OpenAPI 3.0, 3.1 and 3.2 |

## Modules

| Module | Contents |
|---|---|
| `openapi-fn-core` | Functional utilities (`Result`, …), JSON and YAML, multi-document loading, the OpenAPI model, JSON Schema validation, HTTP message parsing and serialization, request/response validation, example generation, Swagger 2.0 conversion |
| `openapi-fn-spring` | Spring Boot 4 runtime: auto-configuration, validation filter, request binding and response mapping for generated controllers, problem details, generated-client support |
| `openapi-fn-spring-test` | MockMvc matchers |
| `openapi-fn-codegen` | The code generator and its CLI |
| `openapi-fn-gradle-plugin` | The `dev.openapifn` Gradle plugin |

Every module is built with 100% line, branch, instruction and method coverage, enforced by
`./gradlew check`.

## Limitations

* **Spring MVC only.** WebFlux has no runtime (generated clients use `RestClient`).
* **Credentials are checked for presence, not verified.** Authentication is Spring Security's job.
  `mutualTLS` requirements count as satisfied.
* **Sequential media types are not streamed.** JSON Lines, SSE and positional multipart bodies are
  read and written whole (a `List`).
* **Swagger 2.0:** only the root document is converted, so documents it references must be valid
  in both versions (plain schemas are). `collectionFormat: tsv`, and non-`csv` formats of header
  and path parameters, have no 3.0 equivalent and become `csv` with a warning.
* **Generic records from `$dynamicRef`:** type variables are used for the template's own
  properties. Inline objects nested in the template fall back to the anchor's default.
* **Remote references are fetched while generating.** The Gradle task does not track their content,
  so re-run it (`--rerun-tasks`) when a remote document changes.
* **Links are checked, not used.** Their target operations must exist, but links have no runtime
  behaviour.
* **Callbacks sharing a URL expression get one receiver each.** Point the expressions at the
  matching receiver paths.
