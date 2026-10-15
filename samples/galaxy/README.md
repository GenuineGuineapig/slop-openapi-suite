# Galaxy sample

[Scalar Galaxy](https://github.com/scalar/scalar/tree/main/packages/galaxy) (MIT), unchanged in
`src/main/openapi/openapi.yaml`.

* `GET /planets` returns `PaginatedResource<Planet>`. The description declares a generic
  `PaginatedResource` (a `$dynamicAnchor` named `itemType`) and binds it to `Planet`. openapi-fn
  generates the generic record `PaginatedResource<T>` and uses `PaginatedResource<Planet>` for this
  response.
* The description resolves `#/components/...` against the document from inside schemas that have
  their own `$id`. Strict JSON Schema would resolve against the `$id` resource instead; openapi-fn
  falls back to the document and logs a warning.
* `mock/` implements the generated interfaces. `PlanetNotifier` is the provider side of the
  `planetCreated`, `planetCreationFailed` and `planetExploded` callbacks and the `newPlanet`
  webhook, using the generated `CallbacksClient` and `WebhooksClient` (callback URLs computed from
  the creation request). `Receivers` is the consumer side. `planetCreated` and `planetExploded`
  are both sent to the `successCallbackUrl` with `POST` and a planet: the description does not tell
  them apart, so loading it warns and `planetCreated` receives both.
* `GalaxyApiIntegrationTest` covers these through the generated clients (with an API key):
  * planet CRUD, including callbacks and webhooks sent to the application itself
  * multipart image upload
  * the `CelestialBody` union (planet or satellite, by `type`)
  * sign-up and tokens
  * rejected requests
