package dev.openapifn.spring.webflux;

import dev.openapifn.spring.security.SpringSecurityCredentials;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;

/**
 * Verifies the credentials of each exchange with what Spring Security authenticated it as (see
 * {@link SpringSecurityCredentials}): exchanges it did not authenticate are rejected.
 */
public final class ReactiveSpringSecurityCredentials {

    private ReactiveSpringSecurityCredentials() {
    }

    /** The verifier of the exchange's reactive security context. */
    public static ExchangeCredentials exchangeCredentials() {
        return exchange -> ReactiveSecurityContextHolder.getContext().map(SecurityContext::getAuthentication).map(SpringSecurityCredentials::of)
            .defaultIfEmpty(SpringSecurityCredentials.of(null));
    }
}
