import dev.openapifn.generator.example.ExampleGenerator;
import dev.openapifn.http.Headers;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.http.HttpResponse;
import dev.openapifn.http.ParameterSerializer;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.model.ApiOperation;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.schema.Direction;
import dev.openapifn.validation.OpenApiValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Request and response validation throughput: example requests and responses of the samples'
 * descriptions (Train Travel, Scalar Galaxy, Redocly Museum), each request routed and validated
 * and its responses validated against the matched operation, in a loop. See tools/benchmark.sh.
 */
public class Benchmark {

    record Exchange(OpenApiValidator validator, HttpRequest request, HttpResponse response) {
    }

    public static void main(String[] args) {
        var root = Path.of(args[0]);
        var seconds = Integer.parseInt(args[1]);
        var exchanges = new ArrayList<Exchange>();
        for (var spec : List.of("train-travel", "galaxy", "museum")) {
            var api = OpenApiLoader.defaults().load(root.resolve("samples/" + spec + "/src/main/openapi/openapi.yaml"))
                .orElseThrow(diagnostics -> new IllegalStateException(diagnostics.toString()));
            var validator = OpenApiValidator.of(api);
            var examples = new ExampleGenerator(api.schemas());
            var authorization = spec.equals("museum") ? "Basic dXNlcjpwYXNz" : "Bearer token";
            api.operations().forEach(operation -> request(api, operation, examples, authorization).ifPresent(request ->
                responses(operation, examples).forEach(response -> exchanges.add(new Exchange(validator, request, response)))));
        }
        var invalid = exchanges.stream().filter(exchange -> !valid(exchange)).count();
        System.out.printf(Locale.ROOT, "%d exchanges (%d invalid: responses of media types the benchmark does not make up)%n", exchanges.size(), invalid);
        for (var round = 1; round <= 3; round++) {
            var count = 0L;
            var end = System.nanoTime() + seconds * 1_000_000_000L;
            while (System.nanoTime() < end) {
                for (var exchange : exchanges) {
                    valid(exchange);
                    count++;
                }
            }
            System.out.printf(Locale.ROOT, "round %d: %,.0f exchanges/s%n", round, count / (double) seconds);
        }
    }

    private static boolean valid(Exchange exchange) {
        var request = exchange.validator().validateRequest(exchange.request());
        return !request.report().hasErrors() && request.match().filter(match -> !exchange.validator().validateResponse(match, exchange.response())
            .hasErrors()).isPresent();
    }

    /** A request with example values for every parameter and a JSON body, as far as they can be made up. */
    private static Optional<HttpRequest> request(OpenApiDocument api, ApiOperation operation, ExampleGenerator examples, String authorization) {
        var path = operation.path();
        var query = new ArrayList<Map.Entry<String, String>>();
        var headers = new LinkedHashMap<String, String>();
        for (var parameter : operation.parameters()) {
            var value = parameter.effectiveSchema().flatMap(schema -> examples.example(schema, Direction.REQUEST));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            var spec = ParameterSerializer.Spec.of(parameter);
            switch (parameter.in()) {
                case PATH -> path = path.replace("{" + parameter.name() + "}", ParameterSerializer.path(spec, value.get()));
                case QUERY -> query.addAll(ParameterSerializer.query(spec, value.get()));
                case HEADER -> headers.put(parameter.name(), ParameterSerializer.header(spec, value.get()));
                default -> {
                    return Optional.empty();
                }
            }
        }
        var prefix = api.model().servers().getFirst().defaultUrl().replaceFirst("^[a-z]+://[^/]*", "").replaceFirst("/$", "");
        var request = HttpRequest.of(operation.method(), prefix + path + (query.isEmpty() ? "" : "?" + ParameterSerializer.queryString(query)))
            .withHeader("Authorization", authorization);
        for (var header : headers.entrySet()) {
            request = request.withHeader(header.getKey(), header.getValue());
        }
        var body = operation.operation().requestBody().map(b -> b.content().get("application/json")).flatMap(mediaType -> mediaType.schema())
            .flatMap(schema -> examples.example(schema, Direction.REQUEST));
        return Optional.of(body.isEmpty() ? request
            : request.withHeader("Content-Type", "application/json").withBody(JsonWriter.compact(body.get()).getBytes(StandardCharsets.UTF_8)));
    }

    /** A response for every status, with a JSON body made up when one is described. */
    private static List<HttpResponse> responses(ApiOperation operation, ExampleGenerator examples) {
        var responses = new ArrayList<HttpResponse>();
        operation.operation().responses().byStatus().forEach((status, response) -> {
            if (!status.matches("[0-9]{3}")) {
                return;
            }
            var type = response.content().keySet().stream().filter(t -> t.contains("json")).findFirst();
            var body = type.flatMap(t -> response.content().get(t).schema()).flatMap(schema -> examples.example(schema, Direction.RESPONSE))
                .map(JsonWriter::compact);
            responses.add(new HttpResponse(Integer.parseInt(status), body.isPresent() ? Headers.of("Content-Type", type.get()) : Headers.EMPTY,
                body.map(text -> text.getBytes(StandardCharsets.UTF_8))));
        });
        return responses;
    }
}
