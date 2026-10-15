package dev.openapifn.schema;

/**
 * The direction of the message being validated, which decides how {@code readOnly} and
 * {@code writeOnly} are enforced: read-only properties must not be sent in requests and
 * write-only properties must not be returned in responses, and neither is then required.
 */
public enum Direction {
    REQUEST,
    RESPONSE,
    NONE
}
