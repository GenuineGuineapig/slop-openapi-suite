package com.example.galaxy.mock;

import com.example.galaxy.api.AuthenticationApi;
import com.example.galaxy.api.CreateUserRequest;
import com.example.galaxy.api.CreateUserResponse;
import com.example.galaxy.api.GetMeResponse;
import com.example.galaxy.api.GetTokenRequest;
import com.example.galaxy.api.GetTokenResponse;
import com.example.galaxy.model.Token;
import com.example.galaxy.model.UnauthorizedError;
import com.example.galaxy.model.User;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Mock sign-up and tokens: any registered email and password pair gets a token. */
@Component
public class MockAuthentication implements AuthenticationApi {

    private final Map<String, String> passwords = new ConcurrentHashMap<>(Map.of("marc@scalar.com", "i-love-scalar"));
    private final AtomicLong ids = new AtomicLong(1);

    @Override
    public CreateUserResponse createUser(CreateUserRequest request) {
        var body = request.body();
        if (body == null) {
            return CreateUserResponse.unauthorized(new UnauthorizedError(null, "Unauthorized", 401L, "Nothing to sign up"));
        }
        if (passwords.putIfAbsent(body.email(), body.password() != null ? body.password() : "") != null) {
            return CreateUserResponse.conflict(new com.example.galaxy.model.Conflict(null, "Conflict", 409L, body.email() + " is already registered"));
        }
        var user = new User(ids.incrementAndGet(), body.name());
        return CreateUserResponse.created(user, UUID.randomUUID(), URI.create("https://galaxy.scalar.com/users/" + user.id()), 12);
    }

    @Override
    public GetTokenResponse getToken(GetTokenRequest request) {
        var credentials = request.body();
        if (credentials == null || credentials.password() == null || !credentials.password().equals(passwords.get(credentials.email()))) {
            return GetTokenResponse.unauthorized(new UnauthorizedError(null, "Unauthorized", 401L, "Wrong email or password"));
        }
        return GetTokenResponse.created(new Token("token-" + UUID.nameUUIDFromBytes(credentials.email().getBytes())), UUID.randomUUID(), 100, 99, 3600);
    }

    @Override
    public GetMeResponse getMe() {
        return GetMeResponse.ok(new User(1L, "Marc"), UUID.randomUUID(), "\"me-1\"", OffsetDateTime.parse("2025-01-01T00:00:00Z"), "private");
    }
}
