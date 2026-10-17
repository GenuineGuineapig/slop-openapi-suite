# openapi-fn samples

Each sample is a standalone Spring Boot 4 application built from one OpenAPI description by
`openapi-fn-maven-plugin`. The repository's build builds them with its own plugin and libraries
(`samples/pom.xml` puts them in it); each is a plain Maven project otherwise. You write no
controllers, request mapping or validation: the build generates them. You implement the generated
`*Api` interfaces (here with mock data, in each sample's `mock` package).

| Sample | Description | Shows |
|---|---|---|
| [petstore](petstore) | Small hand-written API in two documents | The basics: `oneOf` with discriminator, `allOf`, headers, problem responses |
| [museum](museum) | [Redocly Museum API](https://github.com/Redocly/museum-openapi-example) | HTTP basic auth, binary (`image/png`) responses, a webhook |
| [train-travel](train-travel) | [Train Travel API](https://github.com/bump-sh-examples/train-travel-api) (OpenAPI 3.2) | OAuth 2, `QUERY` and custom `SUBSCRIBE` methods, JSON Lines streams, envelope responses, read/write-only payment data, a webhook sent and received |
| [train-travel-webflux](train-travel-webflux) | The same Train Travel API on Spring WebFlux | Generated `Mono`/`Flux` interfaces, reactive controllers and `WebClient` clients, `@WebFluxTest`s, a `Flux` of live updates validated item by item |
| [galaxy](galaxy) | [Scalar Galaxy](https://github.com/scalar/scalar/tree/main/packages/galaxy) | Generic `PaginatedResource<Planet>` from `$dynamicRef`, discriminated unions, multipart upload, several auth schemes, callbacks and a webhook sent and received (callback URLs computed from the request), links followed by the client |

Every sample has two kinds of tests:

* **Generated contract tests**, one `*ControllerTest` per API group. Each runs the generated
  controller against a mocked interface with an example request, and checks the exchange against
  the description.
* **An integration test** (`*IntegrationTest`). It starts the application on a random port and
  calls it through the **generated clients**, with client-side validation enabled. The server
  validates requests and responses in `FAIL` mode, so a mock that breaks the contract fails the
  test.

Build all of them with the repository (`./mvnw verify` at its root), or one after installing
openapi-fn to the local Maven repository; the build needs Java 21, and the repository's wrapper
brings Maven:

```bash
./mvnw install -DskipTests && ./mvnw -f samples/galaxy/pom.xml verify
```

Generated code is under `target/generated-sources/openapi-fn/<name>/`,
`target/generated-test-sources/openapi-fn/<name>/` and `target/generated-resources/openapi-fn/<name>/`.
