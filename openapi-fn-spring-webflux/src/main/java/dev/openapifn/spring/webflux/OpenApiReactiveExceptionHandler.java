package dev.openapifn.spring.webflux;

import dev.openapifn.spring.OpenApiRuntime;
import dev.openapifn.spring.OpenApiValidationException;
import dev.openapifn.spring.Problems;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

/**
 * Answers {@link OpenApiValidationException}s with RFC 9457 problem details in WebFlux
 * applications. The exchange is marked as {@linkplain OpenApiRuntime#REJECTED_ATTRIBUTE rejected},
 * so that the validation filter passes the problem on like its own.
 */
@RestControllerAdvice
public class OpenApiReactiveExceptionHandler {

    @ExceptionHandler(OpenApiValidationException.class)
    public ResponseEntity<String> handle(OpenApiValidationException exception, ServerWebExchange exchange) {
        exchange.getAttributes().put(OpenApiRuntime.REJECTED_ATTRIBUTE, exception.report());
        var status = exception.status();
        var problem = Problems.of(status, "Request validation failed", exchange.getRequest().getPath().value(), exception.report());
        return ResponseEntity.status(status).header("Content-Type", Problems.MEDIA_TYPE).body(problem.toJson());
    }
}
