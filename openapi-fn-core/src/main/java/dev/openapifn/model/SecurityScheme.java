package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The Security Scheme Object, one variant per {@code type}. */
public sealed interface SecurityScheme {

    Optional<String> description();

    /** Whether the scheme is deprecated (3.2). */
    boolean deprecated();

    Map<String, JsonValue> extensions();

    /** {@code type: apiKey}. */
    record ApiKey(String name, ParameterLocation in, Optional<String> description, boolean deprecated, Map<String, JsonValue> extensions)
        implements SecurityScheme {
        public ApiKey {
            extensions = Immutable.map(extensions);
        }
    }

    /** {@code type: http}, e.g. {@code basic} or {@code bearer}. */
    record Http(String scheme, Optional<String> bearerFormat, Optional<String> description, boolean deprecated,
                Map<String, JsonValue> extensions) implements SecurityScheme {
        public Http {
            extensions = Immutable.map(extensions);
        }
    }

    /** {@code type: mutualTLS}. */
    record MutualTls(Optional<String> description, boolean deprecated, Map<String, JsonValue> extensions) implements SecurityScheme {
        public MutualTls {
            extensions = Immutable.map(extensions);
        }
    }

    /** {@code type: oauth2}. */
    record OAuth2(OAuthFlows flows, Optional<String> oauth2MetadataUrl, Optional<String> description, boolean deprecated,
                  Map<String, JsonValue> extensions) implements SecurityScheme {
        public OAuth2 {
            extensions = Immutable.map(extensions);
        }
    }

    /** {@code type: openIdConnect}. */
    record OpenIdConnect(String openIdConnectUrl, Optional<String> description, boolean deprecated, Map<String, JsonValue> extensions)
        implements SecurityScheme {
        public OpenIdConnect {
            extensions = Immutable.map(extensions);
        }
    }
}
