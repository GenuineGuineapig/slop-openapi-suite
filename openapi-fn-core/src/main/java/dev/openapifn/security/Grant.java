package dev.openapifn.security;

import java.util.Objects;
import java.util.Set;

/**
 * What a verified credential grants: who presented it, and the scopes (OAuth 2) or roles (other
 * schemes) it carries. A security requirement's scopes must all be granted.
 *
 * @param subject who presented the credential, such as a user name or a token's subject
 * @param scopes the granted scopes or roles
 */
public record Grant(String subject, Set<String> scopes) {

    public Grant {
        Objects.requireNonNull(subject, "subject");
        scopes = Set.copyOf(scopes);
    }

    public static Grant of(String subject, String... scopes) {
        return new Grant(subject, Set.of(scopes));
    }
}
