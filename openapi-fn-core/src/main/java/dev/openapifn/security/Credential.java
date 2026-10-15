package dev.openapifn.security;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

/**
 * A credential a request presents for a security scheme, as extracted from the request according
 * to the scheme's definition. {@link #scheme()} is the scheme's name under
 * {@code components.securitySchemes}.
 */
public sealed interface Credential {

    /** The name of the security scheme the credential is presented for. */
    String scheme();

    /** An API key ({@code type: apiKey}) from its header, query parameter or cookie. */
    record ApiKey(String scheme, String value) implements Credential {
        public ApiKey {
            Objects.requireNonNull(value, "value");
        }
    }

    /** HTTP basic credentials (RFC 7617). */
    record Basic(String scheme, String user, String password) implements Credential {
        public Basic {
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "Basic[scheme=" + scheme + ", user=" + user + "]";
        }
    }

    /** A bearer token (RFC 6750): of {@code http} {@code bearer}, {@code oauth2} and {@code openIdConnect} schemes. */
    record Bearer(String scheme, String token) implements Credential {
        public Bearer {
            Objects.requireNonNull(token, "token");
        }

        @Override
        public String toString() {
            return "Bearer[scheme=" + scheme + "]";
        }
    }

    /** The credentials of another HTTP authentication scheme, such as {@code digest}. */
    record Authorization(String scheme, String authScheme, String credentials) implements Credential {
        public Authorization {
            Objects.requireNonNull(authScheme, "authScheme");
            Objects.requireNonNull(credentials, "credentials");
        }
    }

    /** The certificate chain a client presented in the TLS handshake ({@code type: mutualTLS}), client certificate first. */
    record ClientCertificate(String scheme, List<X509Certificate> chain) implements Credential {
        public ClientCertificate {
            chain = List.copyOf(chain);
        }
    }
}
