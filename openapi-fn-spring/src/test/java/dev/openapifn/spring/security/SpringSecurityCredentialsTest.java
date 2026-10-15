package dev.openapifn.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.security.Credential;
import dev.openapifn.security.Grant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class SpringSecurityCredentialsTest {

    private static final Credential KEY = new Credential.ApiKey("key", "k");

    @Test
    void authenticatedRequestsAreGrantedTheirAuthorities() {
        GrantedAuthority unnamed = () -> null;
        var authorities = new java.util.ArrayList<GrantedAuthority>(AuthorityUtils.createAuthorityList("SCOPE_pets:read", "ROLE_admin", "plain"));
        authorities.add(unnamed);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("ann", "n/a", authorities);
        assertThat(SpringSecurityCredentials.of(authentication).verify(KEY)).hasValueSatisfying(result -> assertThat(result.toOptional())
            .contains(new Grant("ann", java.util.Set.of("SCOPE_pets:read", "pets:read", "ROLE_admin", "admin", "plain"))));
    }

    @Test
    void otherRequestsAreRejected() {
        var anonymous = new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        var unauthenticated = UsernamePasswordAuthenticationToken.unauthenticated("ann", "pw");
        for (var authentication : java.util.Arrays.asList(null, anonymous, unauthenticated)) {
            assertThat(SpringSecurityCredentials.of(authentication).verify(KEY)).hasValueSatisfying(result ->
                assertThat(result.failure()).contains("the request is not authenticated"));
        }
    }

    @Test
    void theCurrentThreadsAuthenticationIsUsed() {
        var current = SpringSecurityCredentials.current();
        assertThat(current.verify(KEY).orElseThrow().isErr()).isTrue();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ann", "n/a", List.of()));
        try {
            assertThat(current.verify(KEY).orElseThrow().toOptional()).contains(Grant.of("ann"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
