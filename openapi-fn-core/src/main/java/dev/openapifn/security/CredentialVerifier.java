package dev.openapifn.security;

import dev.openapifn.fn.Result;
import java.util.Optional;
import java.util.function.Function;

/**
 * Verifies the credentials requests present: checks a password, a token's signature and expiry,
 * an API key or a client certificate, and tells what it grants. The validator then requires the
 * scopes (or roles) of the operation's security requirement to be granted.
 *
 * <p>A verifier may decline a credential (an empty result), for instance one of a scheme it does
 * not handle; such credentials are only checked for being present and well formed, and their
 * scopes cannot be checked. Verifiers are combined with {@link #orElse}.
 *
 * <pre>{@code
 * var verifier = CredentialVerifier.forScheme("apiKey", credential -> credential instanceof Credential.ApiKey key && keys.contains(key.value())
 *         ? Result.ok(Grant.of("partner", "orders:read")) : Result.err("unknown API key"))
 *     .orElse(CredentialVerifier.forScheme("bearer", credential -> tokens.verify(((Credential.Bearer) credential).token())));
 * }</pre>
 */
@FunctionalInterface
public interface CredentialVerifier {

    /** Declines every credential: credentials are checked for being present and well formed only. */
    CredentialVerifier NONE = credential -> Optional.empty();

    /** What {@code credential} grants, or why it is rejected; empty when this verifier does not verify it. */
    Optional<Result<Grant, String>> verify(Credential credential);

    /** A verifier of the credentials of the scheme named {@code scheme} that declines all others. */
    static CredentialVerifier forScheme(String scheme, Function<Credential, Result<Grant, String>> verify) {
        return credential -> credential.scheme().equals(scheme) ? Optional.of(verify.apply(credential)) : Optional.empty();
    }

    /** This verifier, and {@code other} for the credentials this one declines. */
    default CredentialVerifier orElse(CredentialVerifier other) {
        return credential -> verify(credential).or(() -> other.verify(credential));
    }
}
