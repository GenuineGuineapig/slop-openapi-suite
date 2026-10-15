package dev.openapifn.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.openapifn.fn.Result;
import dev.openapifn.http.HttpRequest;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import dev.openapifn.validation.IssueKeys;
import dev.openapifn.validation.OpenApiValidator;
import dev.openapifn.validation.ValidationIssue;
import java.io.IOException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CredentialVerificationTest {

    private static final OpenApiDocument API = new OpenApiLoader(ResourceLoaders.inMemory(Map.of())).parse(URI.create("mem:/a.yaml"), """
        openapi: 3.1.0
        info: {title: t, version: v}
        paths:
          /read: {get: {security: [{oauth: [pets:read]}], responses: {'204': {description: ok}}}}
          /write: {get: {security: [{oauth: [pets:read, pets:write]}, {key: [admin]}], responses: {'204': {description: ok}}}}
          /basic: {get: {security: [{basic: [staff]}], responses: {'204': {description: ok}}}}
          /digest: {get: {security: [{digest: []}], responses: {'204': {description: ok}}}}
          /both: {get: {security: [{key: [], basic: []}], responses: {'204': {description: ok}}}}
          /mtls: {get: {security: [{mtls: []}], responses: {'204': {description: ok}}}}
          /oidc: {get: {security: [{oidc: [profile]}], responses: {'204': {description: ok}}}}
        components:
          securitySchemes:
            oauth: {type: oauth2, flows: {clientCredentials: {tokenUrl: 'https://t', scopes: {'pets:read': r, 'pets:write': w}}}}
            oidc: {type: openIdConnect, openIdConnectUrl: 'https://o'}
            key: {type: apiKey, name: X-Key, in: header}
            basic: {type: http, scheme: basic}
            digest: {type: http, scheme: digest}
            mtls: {type: mutualTLS}
        """).orElseThrow(d -> new AssertionError(d.toString()));

    /** Tokens name their scopes: {@code read+write}; {@code expired} is rejected. */
    private static final CredentialVerifier TOKENS = CredentialVerifier.forScheme("oauth", credential -> {
        var token = ((Credential.Bearer) credential).token();
        return token.equals("expired") ? Result.err("token expired")
            : Result.ok(new Grant("client", java.util.Set.of(token.split("\\+")).stream().map(scope -> "pets:" + scope).collect(java.util.stream.Collectors.toSet())));
    });
    private static final CredentialVerifier KEYS = CredentialVerifier.forScheme("key", credential ->
        ((Credential.ApiKey) credential).value().equals("root") ? Result.ok(Grant.of("root", "admin")) : Result.ok(Grant.of("user")));
    private static final CredentialVerifier USERS = CredentialVerifier.forScheme("basic", credential -> credential instanceof Credential.Basic basic
        && basic.password().equals("secret") ? Result.ok(Grant.of(basic.user(), "staff")) : Result.err("wrong password"));
    private static final OpenApiValidator VALIDATOR = OpenApiValidator.of(API, OpenApiValidator.Options.DEFAULT.withVerifier(TOKENS.orElse(KEYS)))
        .withVerifier(USERS);

    private static List<ValidationIssue> issues(OpenApiValidator validator, HttpRequest request) {
        return validator.validateRequest(request).report().issues();
    }

    private static List<String> keys(HttpRequest request) {
        return issues(VALIDATOR, request).stream().map(ValidationIssue::key).toList();
    }

    private static HttpRequest get(String path, String header, String value) {
        return HttpRequest.of("GET", path).withHeader(header, value);
    }

    @Test
    void scopesMustBeGranted() {
        assertThat(keys(get("/read", "Authorization", "Bearer read"))).isEmpty();
        assertThat(keys(get("/write", "Authorization", "Bearer read+write"))).isEmpty();
        var forbidden = issues(VALIDATOR, get("/write", "Authorization", "Bearer read"));
        assertThat(forbidden).extracting(ValidationIssue::key).containsExactly(IssueKeys.REQUEST_SECURITY_FORBIDDEN);
        assertThat(forbidden.getFirst().message()).isEqualTo("insufficient scopes: 'oauth' grants [pets:read] but [pets:read, pets:write] are required; "
            + "requires oauth or key");
        assertThat(IssueKeys.status(IssueKeys.REQUEST_SECURITY_FORBIDDEN)).isEqualTo(403);
        // Roles of other schemes work alike (OpenAPI 3.1): the second alternative.
        assertThat(keys(get("/write", "X-Key", "root"))).isEmpty();
        assertThat(keys(get("/write", "X-Key", "guest"))).containsExactly(IssueKeys.REQUEST_SECURITY_FORBIDDEN);
    }

    @Test
    void rejectedCredentialsAreInvalid() {
        var rejected = issues(VALIDATOR, get("/read", "Authorization", "Bearer expired"));
        assertThat(rejected).extracting(ValidationIssue::key).containsExactly(IssueKeys.REQUEST_SECURITY_INVALID);
        assertThat(rejected.getFirst().message()).isEqualTo("credentials for 'oauth' rejected: token expired; requires oauth");
        assertThat(keys(get("/basic", "Authorization", "Basic YW5uOnNlY3JldA=="))).isEmpty();
        assertThat(keys(get("/basic", "Authorization", "Basic YW5uOndyb25n"))).containsExactly(IssueKeys.REQUEST_SECURITY_INVALID);
        // Every scheme of a requirement must be met: the worst one decides.
        var both = HttpRequest.of("GET", "/both").withHeader("X-Key", "guest").withHeader("Authorization", "Basic YW5uOndyb25n");
        assertThat(keys(both)).containsExactly(IssueKeys.REQUEST_SECURITY_INVALID);
        assertThat(keys(HttpRequest.of("GET", "/both").withHeader("X-Key", "guest"))).containsExactly(IssueKeys.REQUEST_SECURITY_MISSING);
        assertThat(keys(HttpRequest.of("GET", "/both").withHeader("X-Key", "guest").withHeader("Authorization", "Basic YW5uOnNlY3JldA=="))).isEmpty();
    }

    @Test
    void declinedCredentialsAreCheckedForPresenceOnly() {
        assertThat(keys(get("/oidc", "Authorization", "Bearer anything"))).isEmpty();
        assertThat(keys(get("/digest", "Authorization", "Digest username=\"u\""))).isEmpty();
        var unverified = OpenApiValidator.of(API);
        assertThat(issues(unverified, get("/write", "Authorization", "Bearer read"))).isEmpty();
        assertThat(unverified.options().verifier().verify(new Credential.ApiKey("key", "k"))).isEmpty();
    }

    @Test
    void credentialsReachTheVerifierAsTheyWerePresented() {
        var seen = new ArrayList<Credential>();
        var recording = OpenApiValidator.of(API).withVerifier(credential -> {
            seen.add(credential);
            return Optional.empty();
        });
        recording.validateRequest(get("/basic", "Authorization", "Basic YW5uOnM6Yw=="));
        recording.validateRequest(get("/digest", "Authorization", "Digest realm=r"));
        recording.validateRequest(get("/oidc", "Authorization", "Bearer abc"));
        recording.validateRequest(get("/both", "X-Key", "k").withHeader("Authorization", "Basic YTpi"));
        assertThat(seen).containsExactly(new Credential.Basic("basic", "ann", "s:c"), new Credential.Authorization("digest", "digest", "realm=r"),
            new Credential.Bearer("oidc", "abc"), new Credential.ApiKey("key", "k"), new Credential.Basic("basic", "a", "b"));
        assertThat(seen.getFirst()).hasToString("Basic[scheme=basic, user=ann]");
        assertThat(seen.get(2)).hasToString("Bearer[scheme=oidc]");
        assertThatThrownBy(() -> new Credential.ApiKey("key", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Credential.Basic("basic", null, "p")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Credential.Basic("basic", "u", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Credential.Bearer("bearer", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Credential.Authorization("digest", null, "c")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Credential.Authorization("digest", "digest", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Grant(null, java.util.Set.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void clientCertificatesAreCheckedWhereTlsEnds() throws IOException, CertificateException {
        X509Certificate certificate;
        try (var pem = CredentialVerificationTest.class.getResourceAsStream("/security/client.pem")) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
        }
        var request = HttpRequest.of("GET", "/mtls");
        // Behind a proxy that terminated TLS, the proxy checked the client.
        assertThat(keys(request)).isEmpty();
        assertThat(keys(request.withClientCertificates(List.of()))).containsExactly(IssueKeys.REQUEST_SECURITY_MISSING);
        assertThat(keys(request.withClientCertificates(List.of(certificate)))).isEmpty();
        var pinned = OpenApiValidator.of(API).withVerifier(CredentialVerifier.forScheme("mtls", credential ->
            ((Credential.ClientCertificate) credential).chain().getFirst().getSubjectX500Principal().getName().equals("CN=trusted")
                ? Result.ok(Grant.of("trusted")) : Result.err("untrusted client")));
        assertThat(issues(pinned, request.withClientCertificates(List.of(certificate)))).extracting(ValidationIssue::message)
            .containsExactly("credentials for 'mtls' rejected: untrusted client; requires mtls");
        assertThat(request.withClientCertificates(List.of(certificate)).withHeader("A", "b").withBody(new byte[0]).clientCertificates())
            .contains(List.of(certificate));
    }
}
