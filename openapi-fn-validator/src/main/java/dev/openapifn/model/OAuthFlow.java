package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The OAuth Flow Object. */
public record OAuthFlow(Optional<String> authorizationUrl, Optional<String> deviceAuthorizationUrl, Optional<String> tokenUrl,
                        Optional<String> refreshUrl, Map<String, String> scopes, Map<String, JsonValue> extensions) {

    public OAuthFlow {
        scopes = Immutable.map(scopes);
        extensions = Immutable.map(extensions);
    }
}
