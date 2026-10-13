package dev.openapifn.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * A JSON number with arbitrary precision. Equality is numeric, so {@code 1}, {@code 1.0} and
 * {@code 1e0} are equal, as JSON Schema requires.
 */
public record JsonNumber(BigDecimal value) implements JsonValue {

    public JsonNumber {
        Objects.requireNonNull(value, "value");
    }

    public static JsonNumber of(long value) {
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    public static JsonNumber of(double value) {
        return new JsonNumber(BigDecimal.valueOf(value));
    }

    public static JsonNumber of(BigInteger value) {
        return new JsonNumber(new BigDecimal(value));
    }

    public static JsonNumber of(BigDecimal value) {
        return new JsonNumber(value);
    }

    /** Whether the value has no fractional part ({@code 1.0} is integral). */
    public boolean isIntegral() {
        return value.stripTrailingZeros().scale() <= 0;
    }

    @Override
    public String typeName() {
        return isIntegral() ? "integer" : "number";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber number && value.compareTo(number.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
