# openapi-fn — design

Date: 2026-10-13. Status: self-approved under a `/goal` directive. The decisions below were
made without interactive review, so treat them as assumptions open to change.

## Intent (what the user asked for)

- A Java 21 library with **full OpenAPI support** (3.0, 3.1, 3.2), written in a **functional style**
  (immutable records, sealed types, a `Result` monad, no nulls in public APIs).
- **Multiple YAML/JSON documents** linked by `$ref`, with the whole schema vocabulary:
  `allOf`/`anyOf`/`oneOf`/`not`, discriminators, `if`/`then`/`else`, `unevaluated*`, `$dynamicRef`, etc.
- **Spring Boot 4** integration: give it a YAML file and get generated models, server controllers,
  controller tests, HTTP clients, plus request and response validation in the filter chain.
- It should **replace openapi-generator and the Atlassian swagger-request-validator**.
- **100% test coverage**, enforced by the build.

## Assumptions (mine, not the user's)

- Build: Gradle (Kotlin DSL) with a Java 21 toolchain. Spring Boot 4.1.x BOM, Jackson 3, JUnit 6.
- Server stack: Spring MVC (servlet). Clients: `RestClient`. WebFlux is out of scope for v1.
- The base package is `dev.openapifn`. Artifacts are named `openapi-fn-*`.
- Generated code depends on the `openapi-fn-spring` runtime (annotations, argument resolver, response mapping).
- The core has no framework dependencies. Its only third-party dependency is `snakeyaml-engine`
  (for YAML 1.2). It includes its own JSON parser and JSON Schema validator.

## Modules

| Module | Responsibility |
|---|---|
| `openapi-fn-core` | `fn` (Result, Validation, Lazy), `json` (immutable JSON tree, parser, writer, pointer), `load` (YAML/JSON → tree with source positions, resource loaders), `model` (OAS records), `parse` (tree → model, multi-document `$ref` resolution), `schema` (JSON Schema validator: OAS 3.0 dialect + 2020-12), `http` (path templates, parameter styles, media types, body parsers), `validation` (request/response validation, issue keys, level policy) |
| `openapi-fn-codegen` | `OpenApiDocument` → Java sources (models, server API + controllers, clients, controller tests, spec resources) plus a CLI |
| `openapi-fn-gradle-plugin` | `openapiFn { spec(...) }` DSL; a generate task wired into `compileJava`/`compileTestJava`/`processResources` |
| `openapi-fn-spring` | Boot 4 auto-configuration: validation filter, problem details, `@OpenApiParam` argument resolver, response mapping, client validation interceptor, enum converters |
| `openapi-fn-spring-test` | MockMvc `ResultMatcher`s and assertions for spec validation, used by generated tests |
| `samples/petstore` | End-to-end sample: multi-file spec → generated code → running tests |

## Key design decisions

1. **The JSON tree is a sealed hierarchy** (`JsonNull | JsonBoolean | JsonNumber(BigDecimal) | JsonString | JsonArray | JsonObject`).
   Numbers compare numerically (`1 == 1.0`), as JSON Schema requires.
2. **Loading**: `ResourceLoader` (file, classpath, in-memory, http-opt-in) → `Document(uri, JsonValue, SourceMap)`.
   All transitively referenced documents are loaded up front into a `DocumentSet`.
3. **References**: non-schema component refs (parameters, responses, bodies, headers, examples, links,
   callbacks, path items, security schemes) are resolved eagerly with cycle detection. In 3.1+, the
   `summary`/`description` siblings override the target. Schemas stay as views over the raw tree
   (`Schema(json, baseUri, pointer)`) and are resolved lazily through a `SchemaRegistry` indexed by
   canonical URI, `$id`, `$anchor` and `$dynamicAnchor`. This keeps recursive schemas cheap and keeps
   schema names available to codegen.
4. **Schema validation** produces annotations (evaluated properties and items) so that
   `unevaluatedProperties` and `unevaluatedItems` work. The dialect is chosen per document version.
   In 3.0 that means `nullable`, boolean `exclusiveMaximum` and ignored `$ref` siblings. Format assertion
   is on by default and configurable. `readOnly`/`writeOnly` are enforced by request/response context.
5. **Request/response validation** is HTTP-neutral (`HttpRequest`/`HttpResponse` records). It does path and
   server-prefix matching, method lookup, security credential presence, parameters (every style/explode
   combination, `content` parameters, the 3.2 `querystring`), body content-type negotiation, and body parsing for
   json, `+json`, form-urlencoded, multipart, text, binary and sequential JSON (`itemSchema`). On the response side it
   checks status lookup (exact → `NXX` → `default`), headers and body. Output is a `ValidationReport` of
   `ValidationIssue(key, level, message, location)`. A `LevelPolicy` remaps levels by key prefix, as
   Atlassian's `LevelResolver` does.
6. **Codegen** emits:
   - models as records: required → `requireNonNull`, immutable collection copies, `withX` withers and a builder.
     Enums carry JSON values. `allOf` is flattened. Discriminated `oneOf`/`anyOf`/`allOf`-parent types become
     sealed interfaces with Jackson subtypes. Non-discriminated unions become sealed interfaces with a
     schema-validating deserializer.
   - per tag: a `XxxApi` delegate interface (`Response op(Request)`), with a request record per operation and a sealed
     response type per operation (one record per status, with typed body and headers); a generated `@RestController`
     that binds through `@OpenApiParam` (spec-exact style handling) and maps sealed responses to `ResponseEntity`
     (501 when no delegate bean exists); and a `XxxClient` built on `RestClient` that serializes parameters per spec
     and decodes responses into the same sealed types.
   - a controller test per tag (`@WebMvcTest`, mocked delegate, example-driven requests, spec validation of responses).
   - a spec resource bundle under `META-INF/openapi-fn/<name>/` plus an index file, so the Boot auto-config finds it with zero configuration.
7. **Coverage**: JaCoCo verification at 100% (instructions, branches, lines, methods) for every library module.
   The plugin is tested in-process with `ProjectBuilder`.
