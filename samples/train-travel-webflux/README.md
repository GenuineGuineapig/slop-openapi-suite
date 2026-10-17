# Train Travel sample on Spring WebFlux

The [Train Travel sample](../train-travel) as a reactive application: the same OpenAPI 3.2
description (licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/),
as stated in its `info.license`), generated with `<reactive>true</reactive>` in the plugin's configuration.

* The generated `*Api` interfaces return `Mono`s, and bodies of sequential media types are `Flux`es.
  `mock/` implements them without blocking: `MockTrips` emits live trip updates with
  `delayElements`, and `MockBookings` answers a new booking once `BookingNotifier` has sent the
  `newBooking` webhook through the generated reactive `WebhooksClient`.
* The generated controllers run on `ReactiveRuntime`, the validation `WebFilter` checks requests and
  responses (streamed ones item by item), and the generated tests are `@WebFluxTest`s with
  `WebTestClient`.
* `TrainTravelApiIntegrationTest` calls the running application through the generated `WebClient`
  clients, with client-side validation enabled, and checks that the `SUBSCRIBE` stream's updates
  arrive one by one.
