package dev.openapifn.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The findings of validating a request or response. */
public record ValidationReport(List<ValidationIssue> issues) {

    public static final ValidationReport EMPTY = new ValidationReport(List.of());

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    /** Whether any issue has level {@link Level#ERROR}. */
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.level() == Level.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(issue -> issue.level() == Level.ERROR).toList();
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    public ValidationReport merge(ValidationReport other) {
        var all = new ArrayList<>(issues);
        all.addAll(other.issues);
        return new ValidationReport(all);
    }

    @Override
    public String toString() {
        return issues.isEmpty() ? "No validation issues" : issues.stream().map(ValidationIssue::toString).collect(Collectors.joining("\n"));
    }
}
