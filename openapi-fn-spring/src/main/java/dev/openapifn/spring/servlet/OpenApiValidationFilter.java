package dev.openapifn.spring.servlet;

import dev.openapifn.http.HttpRequest;
import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.Problems;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.Level;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Validates requests and responses of described paths. Invalid requests are answered with
 * problem details (status 400, 401, 404, 405, 406 or 415) before reaching any handler; the
 * validation result is stored as a request attribute for {@link OpenApiRuntime#bind}. Invalid
 * responses are logged or replaced by a 500 problem, depending on the configuration. Request
 * bodies of described paths are cached so that they can be validated and then consumed.
 */
public final class OpenApiValidationFilter extends OncePerRequestFilter {

    /** How to treat invalid responses. */
    public enum ResponseMode {
        /** Log a warning and pass the response on. */
        LOG,
        /** Replace the response with a 500 problem. */
        FAIL
    }

    /**
     * Filter settings.
     *
     * @param validateRequests whether invalid requests are rejected
     * @param validateResponses whether responses are validated
     * @param responseMode what happens to invalid responses
     * @param rejectUnknownPaths whether requests to paths no description covers get a 404
     * @param excludedPaths path patterns that are not validated
     * @param maxBodySize the largest body that is cached and validated; larger ones pass unvalidated
     */
    public record Settings(boolean validateRequests, boolean validateResponses, ResponseMode responseMode, boolean rejectUnknownPaths,
                           List<String> excludedPaths, long maxBodySize) {

        public Settings {
            excludedPaths = List.copyOf(excludedPaths);
        }
    }

    private static final Log LOG = LogFactory.getLog(OpenApiValidationFilter.class);

    private final OpenApiRuntimes runtimes;
    private final Settings settings;
    private final List<PathPattern> excluded;

    public OpenApiValidationFilter(OpenApiRuntimes runtimes, Settings settings) {
        this.runtimes = runtimes;
        this.settings = settings;
        this.excluded = settings.excludedPaths().stream().map(PathPatternParser.defaultInstance::parse).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = PathContainer.parsePath(ServletMessages.path(request));
        return excluded.stream().anyMatch(pattern -> pattern.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var probe = HttpRequest.of(request.getMethod(), ServletMessages.path(request));
        var runtime = runtimes.routing(probe);
        if (runtime.isEmpty()) {
            if (settings.rejectUnknownPaths()) {
                var report = new ValidationReport(List.of(new ValidationIssue(IssueKeys.REQUEST_PATH_MISSING, Level.ERROR,
                    "no operation is described for path " + probe.path(), "path")));
                problem(response, 404, "Request validation failed", request.getRequestURI(), report);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > settings.maxBodySize()) {
            chain.doFilter(request, response);
            return;
        }
        var cached = CachedBodyRequest.of(request);
        var validation = runtime.get().validator().validateRequest(ServletMessages.toHttpRequest(cached));
        cached.setAttribute(OpenApiRuntime.VALIDATION_ATTRIBUTE, validation);
        if (settings.validateRequests() && validation.report().hasErrors()) {
            var status = IssueKeys.status(validation.report().errors().getFirst().key());
            problem(response, status, "Request validation failed", request.getRequestURI(), validation.report());
            return;
        }
        if (!settings.validateResponses() || validation.match().isEmpty()) {
            chain.doFilter(cached, response);
            return;
        }
        var wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(cached, wrapper);
        var report = runtime.get().validator().validateResponse(validation.match().get(), ServletMessages.response(wrapper));
        if (report.hasErrors() && settings.responseMode() == ResponseMode.FAIL) {
            wrapper.resetBuffer();
            problem(wrapper, 500, "Response validation failed", request.getRequestURI(), report);
        } else if (!report.isEmpty()) {
            LOG.warn("Response of " + request.getMethod() + " " + request.getRequestURI() + " violates the API description:\n" + report);
        }
        wrapper.copyBodyToResponse();
    }

    private static void problem(HttpServletResponse response, int status, String detail, String instance, ValidationReport report)
        throws IOException {
        response.setStatus(status);
        response.setContentType(Problems.MEDIA_TYPE);
        response.getOutputStream().write(Problems.of(status, detail, instance, report).toJson().getBytes(StandardCharsets.UTF_8));
    }
}
