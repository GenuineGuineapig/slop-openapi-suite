package dev.openapifn.spring.webflux;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.spring.ItemStreams;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.Problems;
import dev.openapifn.spring.ResponseMode;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Validates requests and responses of described paths in WebFlux applications, like the servlet
 * filter of Spring MVC applications. Invalid requests are answered with problem details (status
 * 400, 401, 404, 405, 406 or 415) before reaching any handler; the validation result and the read
 * request are stored as exchange attributes for {@link ReactiveRuntime#bind}. Invalid responses are
 * logged or replaced by a 500 problem, depending on the configuration.
 *
 * <p>Bodies of sequential media types (JSON Lines, JSON text sequences, server-sent events,
 * positional multipart) are streamed: request bodies are validated item by item as they are bound
 * (see {@link ReactiveBinding#items}), response bodies as they are written, passing on each item
 * once it is valid. An invalid streamed response becomes a problem while nothing has been sent;
 * later, when failing, the stream ends before the invalid item.
 */
public final class OpenApiValidationWebFilter implements WebFilter, Ordered {

    /**
     * Filter settings.
     *
     * @param validateRequests whether invalid requests are rejected
     * @param validateResponses whether responses are validated
     * @param responseMode what happens to invalid responses
     * @param rejectUnknownPaths whether requests to paths no description covers get a 404
     * @param excludedPaths path patterns that are not validated
     * @param maxBodySize the largest body that is read and validated; larger ones pass unvalidated
     * @param order the order of the filter among the web filters
     */
    public record Settings(boolean validateRequests, boolean validateResponses, ResponseMode responseMode, boolean rejectUnknownPaths,
                           List<String> excludedPaths, long maxBodySize, int order) {

        public Settings {
            excludedPaths = List.copyOf(excludedPaths);
        }
    }

    private static final Log LOG = LogFactory.getLog(OpenApiValidationWebFilter.class);

    private final OpenApiRuntimes runtimes;
    private final Settings settings;
    private final ExchangeCredentials credentials;
    private final List<PathPattern> excluded;

    public OpenApiValidationWebFilter(OpenApiRuntimes runtimes, Settings settings) {
        this(runtimes, settings, ExchangeCredentials.NONE);
    }

    /** @param credentials the verifier of each exchange's credentials, used before the application's verifiers */
    public OpenApiValidationWebFilter(OpenApiRuntimes runtimes, Settings settings, ExchangeCredentials credentials) {
        this.runtimes = runtimes;
        this.settings = settings;
        this.credentials = credentials;
        this.excluded = settings.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    }

    @Override
    public int getOrder() {
        return settings.order();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var path = ReactiveMessages.path(request);
        if (excluded.stream().anyMatch(pattern -> pattern.matches(PathContainer.parsePath(path)))) {
            return chain.filter(exchange);
        }
        var probe = HttpRequest.of(request.getMethod().name(), path);
        var routed = runtimes.routing(probe);
        if (routed.isEmpty()) {
            if (settings.rejectUnknownPaths()) {
                var report = new ValidationReport(List.of(new ValidationIssue(IssueKeys.REQUEST_PATH_MISSING, Level.ERROR,
                    "no operation is described for path " + path, "path")));
                return problem(exchange.getResponse(), 404, "Request validation failed", request.getPath().value(), report);
            }
            return chain.filter(exchange);
        }
        var streamed = ItemStreams.sequential(request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        if (!streamed && request.getHeaders().getContentLength() > settings.maxBodySize()) {
            return chain.filter(exchange);
        }
        var message = streamed ? Mono.just(ReactiveMessages.head(request)) : ReactiveMessages.read(request);
        var verifying = credentials.verifier(exchange).map(routed.get()::withVerifier).defaultIfEmpty(routed.get());
        return verifying.zipWith(message).flatMap(pair -> {
            var runtime = pair.getT1();
            var read = pair.getT2();
            var validation = streamed ? runtime.validateHead(read) : runtime.validate(read);
            var forwarded = streamed ? exchange : exchange.mutate().request(cached(request, read)).build();
            forwarded.getAttributes().put(OpenApiRuntime.VALIDATION_ATTRIBUTE, validation);
            if (!streamed) {
                forwarded.getAttributes().put(ReactiveRuntime.MESSAGE_ATTRIBUTE, read);
            }
            if (settings.validateRequests() && validation.report().hasErrors()) {
                var status = IssueKeys.status(validation.report().errors().getFirst().key());
                return problem(exchange.getResponse(), status, "Request validation failed", request.getPath().value(), validation.report());
            }
            if (!settings.validateResponses() || validation.match().isEmpty()) {
                return chain.filter(forwarded);
            }
            var response = new ValidatingResponse(forwarded, runtime.validator(), validation.match().get(), settings.responseMode() == ResponseMode.FAIL,
                report -> LOG.warn("Response of " + request.getMethod() + " " + request.getPath() + " violates the API description:\n" + report));
            return chain.filter(forwarded.mutate().response(response).build());
        });
    }

    /** {@code request} whose body is the one already read. */
    private static ServerHttpRequest cached(ServerHttpRequest request, HttpRequest read) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.defer(() -> Flux.fromIterable(read.body().stream().map(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance::wrap)
                    .map(DataBuffer.class::cast).toList()));
            }
        };
    }

    static Mono<Void> problem(ServerHttpResponse response, int status, String detail, String instance, ValidationReport report) {
        response.setStatusCode(HttpStatusCode.valueOf(status));
        response.getHeaders().setContentType(MediaType.parseMediaType(Problems.MEDIA_TYPE));
        var bytes = Problems.of(status, detail, instance, report).toJson().getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
