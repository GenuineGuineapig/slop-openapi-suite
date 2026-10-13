package dev.openapifn.spring;

import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.ValidationReport;

/** Thrown when a request cannot be bound because it violates the API description. */
public class OpenApiValidationException extends RuntimeException {

    private final transient ValidationReport report;

    public OpenApiValidationException(ValidationReport report) {
        super(report.toString());
        this.report = report;
    }

    public ValidationReport report() {
        return report;
    }

    /** The HTTP status that fits the first error (400 unless routing, security or negotiation failed). */
    public int status() {
        return report.errors().stream().findFirst().map(issue -> IssueKeys.status(issue.key())).orElse(400);
    }
}
