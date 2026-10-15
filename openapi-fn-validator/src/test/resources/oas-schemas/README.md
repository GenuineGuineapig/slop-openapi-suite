# OpenAPI Specification JSON Schemas

The OpenAPI Initiative's JSON Schemas for OpenAPI descriptions, copied unchanged from the
[OpenAPI-Specification](https://github.com/OAI/OpenAPI-Specification) repository and licensed under
the Apache License 2.0 (see `LICENSE`):

* `v2.0/schema.json`: `_archive_/schemas/v2.0/schema.json` of `main` (ffb3e579)
* `v3.0/schema.yaml`: `_archive_/schemas/v3.0/schema.yaml` of `main` (ffb3e579)
* `v3.1/*.yaml`: `src/schemas/validation/*.yaml` of `v3.1-dev` (551e3df1)
* `v3.2/*.yaml`: `src/schemas/validation/*.yaml` of `v3.2-dev` (85f1be31)

`OfficialSchemaTest` uses them as an independent oracle: every description the tests know (the
OpenAPI Initiative's own pass and fail cases, the samples and the fixtures) is validated against the
schema of its version, and openapi-fn must judge it the same way.
