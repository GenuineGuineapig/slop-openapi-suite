package dev.openapifn.spring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Answers {@link OpenApiValidationException}s with RFC 9457 problem details. */
@RestControllerAdvice
public class OpenApiExceptionHandler {

    @ExceptionHandler(OpenApiValidationException.class)
    public ResponseEntity<String> handle(OpenApiValidationException exception, HttpServletRequest request) {
        var status = exception.status();
        var problem = Problems.of(status, "Request validation failed", request.getRequestURI(), exception.report());
        return ResponseEntity.status(status).header("Content-Type", Problems.MEDIA_TYPE).body(problem.toJson());
    }
}
