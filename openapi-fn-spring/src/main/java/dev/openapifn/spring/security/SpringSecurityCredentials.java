package dev.openapifn.spring.security;

import dev.openapifn.fn.Result;
import dev.openapifn.security.CredentialVerifier;
import dev.openapifn.security.Grant;
import java.util.HashSet;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Verifies credentials with what Spring Security authenticated a request as: Spring Security
 * checks passwords, token signatures and certificates; the validator then requires the operation's
 * scopes or roles among the authentication's authorities. An authority {@code SCOPE_pets:read}
 * grants the scope {@code pets:read}, {@code ROLE_admin} the role {@code admin}; every authority
 * also counts as it is. Requests Spring Security did not authenticate are rejected.
 */
public final class SpringSecurityCredentials {

    private SpringSecurityCredentials() {
    }

    /** Verifies with the authentication of the current thread's request (Spring MVC). */
    public static CredentialVerifier current() {
        return credential -> Optional.of(grant(SecurityContextHolder.getContext().getAuthentication()));
    }

    /** Verifies with {@code authentication}, such as the one of a reactive request; {@code null} for none. */
    public static CredentialVerifier of(@Nullable Authentication authentication) {
        var grant = grant(authentication);
        return credential -> Optional.of(grant);
    }

    /** What {@code authentication} grants: its name, and its authorities as scopes and roles. */
    static Result<Grant, String> grant(@Nullable Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            return Result.err("the request is not authenticated");
        }
        var scopes = new HashSet<String>();
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            var authority = granted.getAuthority();
            if (authority != null) {
                scopes.add(authority);
                scopes.add(authority.replaceFirst("^(SCOPE_|ROLE_)", ""));
            }
        }
        return Result.ok(new Grant(authentication.getName(), scopes));
    }
}
