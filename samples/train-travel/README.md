# Train Travel sample

The [Train Travel API](https://github.com/bump-sh-examples/train-travel-api) by Bump.sh, an
OpenAPI 3.2 description, unchanged in `src/main/openapi/openapi.yaml`. That file is licensed under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/), as stated in its `info.license`.

* `mock/` implements the generated `StationsApi`, `TripsApi`, `BookingsApi`, `PaymentsApi` and
  `WebhooksApi`. `BookingNotifier` is the provider side of the `newBooking` webhook: it sends new
  bookings to subscribers with the generated `WebhooksClient`.
* `TrainTravelApiIntegrationTest` covers these, using the generated clients with a bearer token:
  * station search with `GET` and with the 3.2 `QUERY` method (a request body on a safe request)
  * trip search and the custom `SUBSCRIBE` method, which streams `TripUpdate`s as JSON Lines: the
    mock produces them one by one, and the test checks that they arrive one by one. Clients that
    only accept server-sent events get the description's other variant, `OkEventStream`.
  * booking, conflict detection, payment by card (write-only card data is not echoed) and cancellation
  * the `newBooking` webhook, which the application sends to itself
* The description's examples contain malformed UUIDs; the generated tests skip invalid examples
  and derive valid ones from the schemas.
