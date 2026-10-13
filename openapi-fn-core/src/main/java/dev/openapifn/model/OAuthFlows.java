package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The OAuth Flows Object; {@code deviceAuthorization} is new in 3.2. */
public record OAuthFlows(Optional<OAuthFlow> implicit, Optional<OAuthFlow> password, Optional<OAuthFlow> clientCredentials,
                         Optional<OAuthFlow> authorizationCode, Optional<OAuthFlow> deviceAuthorization, Map<String, JsonValue> extensions) {

    public OAuthFlows {
        extensions = Immutable.map(extensions);
    }
}
