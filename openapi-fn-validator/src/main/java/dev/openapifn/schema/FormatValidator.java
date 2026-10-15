package dev.openapifn.schema;

import dev.openapifn.json.JsonValue;
import java.util.Optional;
import java.util.function.Predicate;

/** Checks a value against a {@code format}. */
@FunctionalInterface
public interface FormatValidator {

    /** @return a description of the problem, or empty when {@code instance} conforms */
    Optional<String> validate(JsonValue instance);

    /** A validator for string values; other types always conform. */
    static FormatValidator forStrings(Predicate<String> valid, String expected) {
        return instance -> instance.asString().filter(valid.negate()).map(value -> "must be a valid " + expected);
    }
}
