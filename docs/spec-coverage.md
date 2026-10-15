# Specification coverage

This document records how openapi-fn covers the OpenAPI Specification 3.2. 3.2 is a superset of
3.1 and 3.0, so those are covered too; Swagger / OpenAPI 2.0 is converted to 3.0 on load. Every
field of every object is read into the model (`openapi-fn-core`, `dev.openapifn.model`). The table
below says what each feature *does* in the parts that act on the model:

* **Validation**: `OpenApiValidator`, also behind the Spring filter and the test matchers
* **Runtime**: Spring binding and response mapping, for Spring MVC (`openapi-fn-spring`) and
  WebFlux (`openapi-fn-spring-webflux`)
* **Codegen**: generated code (`openapi-fn-codegen`)

"Model" means the information is available through the model but has no behaviour; this is
usually because the specification gives it none (descriptions, documentation links).

## How this is verified

Beyond the project's own tests (100% line, branch, instruction and method coverage), conformance is
checked against material openapi-fn did not write:

| Evidence | What must hold |
|---|---|
| The [JSON Schema Test Suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite), drafts 4, 6, 7, 2019-09 and 2020-12 (`JsonSchemaTestSuiteTest`) | All 8888 cases pass, required and optional, none skipped |
| The OpenAPI Initiative's schema tests: 3.1 and 3.2 `pass` and `fail` descriptions, 3.0 examples (`OpenApiConformanceSuiteTest`) | `pass` descriptions load without errors, `fail` descriptions are rejected |
| The OpenAPI Initiative's JSON Schemas for 2.0, 3.0, 3.1 and 3.2 as an oracle (`OfficialSchemaTest`) | Every one of those descriptions is judged by the official schema as openapi-fn judges it; the samples' descriptions (Train Travel, Scalar Galaxy, Redocly Museum, a petstore) and the fixtures are valid for both; Swagger 2.0 descriptions are converted to descriptions the official 3.0 schema accepts |
| The samples (`samples/`), built on third-party descriptions | Generated controllers, clients and tests work end to end on each, for Spring MVC and WebFlux |

## Documents and references

| Feature | Support |
|---|---|
| YAML 1.2 and JSON, several documents | Loaded with source positions for diagnostics. Documents are cached and identified by URI |
| `$ref` to local, relative, absolute, classpath and remote (`http(s)`) documents | Resolved at load time. Remote documents are fetched while generating and bundled with the generated code, so the runtime never fetches them |
| Reference Object `summary` / `description` overrides | Model |
| `$self` (3.2) | Base URI for resolution; documents are also addressable by it |
| Path Item `$ref` (with sibling merging) | Validation, runtime, codegen |
| `components` of every kind, including `pathItems` and `mediaTypes` (3.2) | Validation, runtime, codegen (by reference) |
| `jsonSchemaDialect` | Selects the dialect (draft 4 to 2020-12, OAS 3.0); unknown dialects fall back to 2020-12 with a warning |
| Swagger / OpenAPI 2.0 | Bundled into one document (external schemas copied into `definitions`, external parameters, responses and path items inlined; see `Swagger2Bundler`) and converted to 3.0 (`Swagger2Converter`) |

## OpenAPI, Info, Servers, Tags

| Feature | Support |
|---|---|
| `info` (with `summary`, `license.identifier`), `externalDocs`, tags (`summary`, `parent`, `kind`) | Model; tags group generated APIs |
| `servers` with variables (`enum`, `default`) | Validation routes with the path of each server URL (variables substituted). Generated clients default to the first URL; controllers are mapped to its path (or `basePath` / `openapi-fn.specs.<api>.base-path`) |
| Path-item and operation `servers` | Validation routing |
| `webhooks` (3.1) | Validation; codegen generates receivers and senders |

## Paths and operations

| Feature | Support |
|---|---|
| Path templating, templated segments with literals, matching precedence | Validation routing. Concrete paths win over templated ones |
| `get` … `trace`, `query` (3.2), `additionalOperations` (3.2) | Validation, runtime, codegen (custom methods are dispatched by the generated controller) |
| `HEAD` falling back to `GET` | Validation (configurable) |
| `operationId` (unique; duplicates are errors) | Names Java methods. Synthesized as `METHOD path` when missing |
| `deprecated` | Codegen: `@Deprecated` on operations, models and properties |
| `security` (operation, global, empty), scopes and (3.1+) roles | Validation: one alternative's credentials must be present, well formed (API key header/query/cookie, HTTP schemes, OAuth 2 and OpenID Connect bearer tokens, `mutualTLS` client certificates), verified (`CredentialVerifier`, or Spring Security's authentication) and grant the required scopes or roles (401 / 403). Generated tests send credentials |
| `callbacks` | Validation (by operation); codegen generates receivers, senders, and methods computing the callback URL from the subscribing request (and response). Callbacks with one URL expression are received at one path, told apart by method and then by request; requests described alike are reported when loading. Callback ids are unique (`<owner> <name> METHOD`) |
| `links` | Load-time checks (targets exist, runtime expressions are well-formed). Codegen: a client method per link builds the linked operation's request from a request and its response (`operationId`, or `operationRef` to a path or webhook operation); `server` is noted in its Javadoc |
| Runtime expressions | `RuntimeExpressions` (core) parses and evaluates every form of the ABNF, including `{…}` templates; generated callback URL methods use them |

## Parameters

| Feature | Support |
|---|---|
| `in`: `path`, `query`, `querystring` (3.2), `header`, `cookie` | Validation, runtime binding, generated clients and tests |
| `style`: `matrix`, `label`, `simple`, `form`, `spaceDelimited`, `pipeDelimited`, `deepObject`, `cookie` (3.2); `explode` | Parsing and serialization of primitives, arrays and objects (`ParameterParser`, `ParameterSerializer`) |
| `allowReserved`, `allowEmptyValue`, `required`, `deprecated` | Validation and serialization |
| `content` (single media type, e.g. JSON in a query parameter) | Validation, binding, serialization |
| Unknown query parameters, the `Accept`, `Content-Type` and `Authorization` header rules | Validation (as configurable issues) |
| `example` / `examples` (`dataValue`, `serializedValue`, `externalValue`, 3.2) | Generated tests use them (and synthesize valid ones when there are none) |

## Request bodies, responses and media types

| Feature | Support |
|---|---|
| `requestBody` (`required`), media type ranges (`*/*`, `image/*`) and their precedence | Validation, runtime content negotiation, codegen |
| JSON and `+json` | JSON Schema validation, Jackson binding |
| XML (`xml`: `name`, `namespace`, `prefix`, `attribute`, `wrapped`, `nodeType` 3.2) | Parsing to JSON for validation, writing from JSON |
| `application/x-www-form-urlencoded` with `encoding` styles | Validation, binding, generated clients |
| `multipart/form-data` with `encoding` (`contentType`, `headers`, nested) | Validation of parts (content types, headers, schemas), binding (files as `FilePart`), generated clients and tests |
| `itemSchema`, `prefixEncoding`, `itemEncoding` (3.2): `application/jsonl`, `application/x-ndjson`, `application/json-seq`, `text/event-stream`, positional `multipart/mixed` | Validation per item, also while streaming (`ItemFramer`, `OpenApiValidator.requestItems` / `responseItems`). Streamed in both directions by the runtime and generated code as `Stream<T>` (`Flux<T>` with WebFlux) |
| Text and binary bodies, `format: binary`, `contentMediaType` | Validation, `FilePart` / `String` in generated code |
| Responses by status, by range (`2XX`) and `default`; Response `summary` (3.2) | Validation, sealed response types with a record per variant |
| Response `headers` (`required`, schema, `content`) | Validation, typed components of response records |

## Schemas

| Feature | Support |
|---|---|
| JSON Schema drafts 4, 6, 7, 2019-09, 2020-12 and the OAS 3.0 dialect | `SchemaValidator`, checked against the official JSON-Schema-Test-Suite: every required and optional test passes. Each draft is processed as itself, also when referenced from another (keywords it lacks are ignored; the OAS 3.0 dialect honours them all) |
| `$schema` naming a custom metaschema with `$vocabulary` | Only the enabled vocabularies' keywords apply; `format-assertion` makes `format` an assertion; an unknown required vocabulary is an error |
| `$ref` with siblings, `$id`, `$anchor`, `$dynamicRef` / `$dynamicAnchor`, `$recursiveRef` | Validation. Codegen turns dynamic-anchor templates into generic records. A `#/…` reference missing from an `$id` resource falls back to the document, with a warning |
| `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else`, `dependentSchemas`, `unevaluatedProperties` / `unevaluatedItems`, `prefixItems`, `contains` | Validation. Codegen: `allOf` merges properties (later members refine earlier ones), `oneOf`/`anyOf` become sealed unions |
| `discriminator` (`mapping`, `defaultMapping` 3.2, implicit names) | Validation picks the member to report on; codegen generates Jackson subtype information |
| `readOnly` / `writeOnly` | Validation by direction (webhook and callback requests are provider-written, like responses); codegen relaxes required-ness accordingly |
| `nullable` (3.0), `type: [.., 'null']` (3.1) | Validation; `@Nullable` in generated code |
| `format` (dates, times, durations, email, IDN hostnames and emails, IPv4/6, URIs and IRIs, URI templates, JSON pointers, regex, UUID, and the OAS integer/number/byte/binary formats) | Validation (`assert-formats`); Java types in generated code (`LocalDate`, `OffsetDateTime`, `UUID`, `URI`, `byte[]` …) |
| `default`, `const`, `enum`, `examples` | Validation; codegen defaults and enums; examples for generated tests |
| `externalDocs`, `example` | Model / generated tests |
| Extensions `x-enum-varnames`, `x-enum-descriptions` | Codegen |

## Security schemes

| Feature | Support |
|---|---|
| `apiKey` (header, query, cookie), `http` (basic, bearer, others), `oauth2` (all flows including `deviceAuthorization` 3.2, `oauth2MetadataUrl`), `openIdConnect`, `mutualTLS`, `deprecated` (3.2) | Validation extracts each scheme's credential and verifies it (see above): with `CredentialVerifier`s, or with what Spring Security authenticated the request as |

## Specification extensions

`x-` fields are kept on every object that allows them and are available through the model.
