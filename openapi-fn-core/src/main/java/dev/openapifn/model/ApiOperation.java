package dev.openapifn.model;

import dev.openapifn.schema.Direction;
import java.util.List;
import java.util.Optional;

/**
 * An operation together with its context and with path-item level settings already merged in.
 *
 * @param path the path template; for webhooks the webhook name, for callbacks the runtime expression of the URL
 * @param method upper-case HTTP method
 * @param pathItem the containing path item
 * @param operation the operation
 * @param parameters effective parameters (path item parameters overridden by operation parameters)
 * @param security effective security requirements (operation level, else global)
 * @param servers effective servers (operation, else path item, else global)
 * @param kind where the operation is described
 */
public record ApiOperation(String path, String method, PathItem pathItem, Operation operation, List<Parameter> parameters,
                           List<SecurityRequirement> security, List<Server> servers, Kind kind) {

    /** Where an operation is described, which decides who sends its requests. */
    public enum Kind {
        /** Under {@code paths}: the API serves it. */
        PATH,
        /** Under {@code webhooks} (3.1+): the API provider sends it to subscribers. */
        WEBHOOK,
        /** In the {@code callbacks} of an operation: the API provider sends it to a URL taken from the triggering exchange. */
        CALLBACK
    }

    public ApiOperation {
        parameters = List.copyOf(parameters);
        security = List.copyOf(security);
        servers = List.copyOf(servers);
    }

    public Optional<String> operationId() {
        return operation.operationId();
    }

    /**
     * How {@code readOnly} and {@code writeOnly} apply to the request: webhook and callback requests
     * are written by the API provider, like responses.
     */
    public Direction requestDirection() {
        return kind == Kind.PATH ? Direction.REQUEST : Direction.RESPONSE;
    }

    /** How {@code readOnly} and {@code writeOnly} apply to responses: webhook and callback responses come from the receiver. */
    public Direction responseDirection() {
        return kind == Kind.PATH ? Direction.RESPONSE : Direction.REQUEST;
    }

    /** A human readable identifier: the operation id, else {@code METHOD path}. */
    public String displayName() {
        return operationId().orElse(method + " " + path);
    }
}
