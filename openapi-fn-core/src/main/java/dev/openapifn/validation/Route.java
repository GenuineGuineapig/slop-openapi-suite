package dev.openapifn.validation;

import java.util.Set;

/** The outcome of routing a request to an operation. */
public sealed interface Route {

    /** An operation was found. */
    record Found(OperationMatch match) implements Route {
    }

    /** The path exists but does not support the request method. */
    record MethodNotAllowed(String path, Set<String> allowed) implements Route {
        public MethodNotAllowed {
            allowed = Set.copyOf(allowed);
        }
    }

    /** No path template matches. */
    record NotFound() implements Route {
    }
}
