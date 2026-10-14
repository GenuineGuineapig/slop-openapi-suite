package dev.openapifn.spring.client;

import dev.openapifn.validation.ValidationReport;

/**
 * Thrown by a validating client when the request it is about to send, or the response it
 * received, violates the API description. Unlike {@link dev.openapifn.spring.OpenApiValidationException}
 * it does not describe the request a server is handling, so it is not answered with a 4xx.
 */
public class ClientValidationException extends RuntimeException {

    private final transient ValidationReport report;

    public ClientValidationException(String message, ValidationReport report) {
        super(message + "\n" + report);
        this.report = report;
    }

    public ValidationReport report() {
        return report;
    }
}
