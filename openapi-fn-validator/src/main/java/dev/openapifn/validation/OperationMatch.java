package dev.openapifn.validation;

import dev.openapifn.model.ApiOperation;
import java.util.Map;

/**
 * An operation matched to a request.
 *
 * @param operation the operation
 * @param pathValues raw (percent-encoded) values of the path template expressions
 */
public record OperationMatch(ApiOperation operation, Map<String, String> pathValues) {

    public OperationMatch {
        pathValues = Map.copyOf(pathValues);
    }
}
