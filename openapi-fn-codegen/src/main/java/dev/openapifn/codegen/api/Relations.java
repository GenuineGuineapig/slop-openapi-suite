package dev.openapifn.codegen.api;

import dev.openapifn.model.Link;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * How operations relate across groups, for the clients: where Link Objects lead, and which
 * operation subscribes to each callback.
 *
 * @param targets the generated operation a Link Object leads to, if any
 * @param owners the operation whose requests subscribe to each callback, by the callback's operation id
 */
public record Relations(Function<Link, Optional<OperationModel>> targets, Map<String, OperationModel> owners) {

    public Relations {
        owners = Map.copyOf(owners);
    }
}
