package dev.openapifn.spring.webflux;

import dev.openapifn.security.CredentialVerifier;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The credential verifier of one exchange, such as one knowing whom Spring Security authenticated
 * it as; the validation web filter verifies credentials with it before the application's
 * verifiers. Empty when there is none.
 */
@FunctionalInterface
public interface ExchangeCredentials {

    /** No verifier per exchange. */
    ExchangeCredentials NONE = exchange -> Mono.empty();

    Mono<CredentialVerifier> verifier(ServerWebExchange exchange);
}
