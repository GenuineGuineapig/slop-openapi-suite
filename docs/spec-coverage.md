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
| `security` (operation, global, empty) | Validation: credentials present for one alternative (API key header/query/cookie, HTTP schemes, OAuth 2 and OpenID Connect bearer tokens; `mutualTLS` counts as satisfied). Generated tests send credentials |
| `callbacks` | Validation (by operation); codegen generates receivers and senders. Callback ids are unique (`<owner> <name> METHOD`) |
| `links` | Load-time check that targets exist (`operationId`, local `operationRef`); no runtime behaviour |

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
| JSON Schema drafts 4, 6, 7, 2019-09, 2020-12 and the OAS 3.0 dialect | `SchemaValidator`, checked against the official JSON-Schema-Test-Suite (all required and optional tests, except custom `$vocabulary` metaschemas and one draft-4 hostname case) |
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
| `apiKey` (header, query, cookie), `http` (basic, bearer, others), `oauth2` (all flows including `deviceAuthorization` 3.2, `oauth2MetadataUrl`), `openIdConnect`, `mutualTLS`, `deprecated` (3.2) | Model; validation checks credentials are present (see above). Verifying credentials is left to Spring Security |

## Specification extensions

`x-` fields are kept on every object that allows them and are available through the model.
