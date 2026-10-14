package dev.openapifn.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.model.Server;
import dev.openapifn.schema.SchemaValidator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValidationModelTest {

    @Test
    void statusCodes() {
        assertThat(IssueKeys.status(IssueKeys.REQUEST_PATH_MISSING)).isEqualTo(404);
        assertThat(IssueKeys.status(IssueKeys.REQUEST_OPERATION_NOT_ALLOWED)).isEqualTo(405);
        assertThat(IssueKeys.status(IssueKeys.REQUEST_SECURITY_MISSING)).isEqualTo(401);
        assertThat(IssueKeys.status(IssueKeys.REQUEST_CONTENT_TYPE_NOT_ALLOWED)).isEqualTo(415);
        assertThat(IssueKeys.status(IssueKeys.REQUEST_ACCEPT_NOT_ACCEPTABLE)).isEqualTo(406);
        assertThat(IssueKeys.status(IssueKeys.REQUEST_BODY_SCHEMA + ".required")).isEqualTo(400);
    }

    @Test
    void reportsAndIssues() {
        var error = new ValidationIssue("a.b", Level.ERROR, "broken", "body/x");
        var warning = new ValidationIssue("c", Level.WARN, "hmm", "");
        assertThat(error).hasToString("ERROR a.b [body/x]: broken");
        assertThat(warning).hasToString("WARN c: hmm");
        assertThat(new ValidationReport(List.of(warning, warning, error)).issues()).containsExactly(warning, error);
        var report = new ValidationReport(List.of(warning)).merge(new ValidationReport(List.of(error)));
        assertThat(report.hasErrors()).isTrue();
        assertThat(report.errors()).containsExactly(error);
        assertThat(report.isEmpty()).isFalse();
        assertThat(report).hasToString("WARN c: hmm\nERROR a.b [body/x]: broken");
        assertThat(ValidationReport.EMPTY).hasToString("No validation issues");
        assertThat(ValidationReport.EMPTY.hasErrors()).isFalse();
        assertThatThrownBy(() -> new ValidationIssue(null, Level.ERROR, "m", "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationIssue("k", null, "m", "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationIssue("k", Level.ERROR, null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ValidationIssue("k", Level.ERROR, "m", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void levelPolicy() {
        var policy = LevelPolicy.DEFAULT.with("request.body", Level.WARN).with("request.body.schema.required", Level.INFO);
        assertThat(policy.levelFor("request.body.schema.required")).isEqualTo(Level.INFO);
        assertThat(policy.levelFor("request.body.schema.type")).isEqualTo(Level.WARN);
        assertThat(policy.levelFor("request.bodyless")).isEqualTo(Level.ERROR);
        assertThat(policy.levelFor("response")).isEqualTo(Level.ERROR);
        assertThat(new LevelPolicy(Level.IGNORE, Map.of()).levelFor("anything.at.all")).isEqualTo(Level.IGNORE);
    }

    @Test
    void routesAndOptions() {
        assertThat(Router.prefix(new Server("https://example.com", Optional.empty(), Optional.empty(), Map.of(), Map.of()))).isEqualTo("/");
        assertThat(Router.prefix(new Server("https://example.com/api?x=1", Optional.empty(), Optional.empty(), Map.of(), Map.of()))).isEqualTo("/api");
        assertThat(Router.prefix(new Server("v1", Optional.empty(), Optional.empty(), Map.of(), Map.of()))).isEqualTo("/v1");
        assertThat(new Route.MethodNotAllowed("/a", Set.of("GET")).allowed()).containsExactly("GET");
        var options = OpenApiValidator.Options.DEFAULT.withSchema(SchemaValidator.Options.DEFAULT.withAssertFormats(false));
        assertThat(options.schema().assertFormats()).isFalse();
    }
}
