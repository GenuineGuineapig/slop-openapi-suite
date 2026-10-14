# Museum sample

[Redocly's Museum API](https://github.com/Redocly/museum-openapi-example) (MIT), unchanged in
`src/main/openapi/openapi.yaml`.

* `mock/` implements the generated `OperationsApi`, `EventsApi`, `TicketsApi` and `WebhooksApi`
  (the receiver of the `publishNewEvent` webhook) with in-memory data.
* `MuseumApiIntegrationTest` calls the API with the generated clients over HTTP. A
  `RestClientCustomizer` adds the Basic credentials the API requires. The test also sends the
  webhook through the generated `WebhooksClient` and checks that requests without credentials or
  with an invalid body are rejected.
* `build.gradle.kts` serves the API at `/` (`basePath = "/"`) instead of the path of the
  description's mock server.
