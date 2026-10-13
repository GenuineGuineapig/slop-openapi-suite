package dev.openapifn.spring;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.validation.ValidationIssue;
import dev.openapifn.validation.ValidationReport;
import org.springframework.http.HttpStatus;

/** RFC 9457 problem details for validation failures. */
public final class Problems {

    /** The media type of problem details. */
    public static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {
    }

    /**
     * A problem object with the standard members and an {@code errors} array of
     * {@code {key, message, location}} entries.
     */
    public static JsonObject of(int status, String detail, String instance, ValidationReport report) {
        var title = HttpStatus.resolve(status);
        return JsonObject.builder()
            .put("type", "about:blank")
            .put("title", title == null ? "Error" : title.getReasonPhrase())
            .put("status", status)
            .put("detail", detail)
            .put("instance", instance)
            .put("errors", new JsonArray(report.issues().stream().map(Problems::issue).toList()))
            .build();
    }

    private static JsonValue issue(ValidationIssue issue) {
        return JsonObject.builder()
            .put("key", issue.key())
            .put("level", issue.level().name())
            .put("message", issue.message())
            .put("location", issue.location())
            .build();
    }
}
