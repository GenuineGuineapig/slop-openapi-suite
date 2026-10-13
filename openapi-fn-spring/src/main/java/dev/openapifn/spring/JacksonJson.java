package dev.openapifn.spring;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.math.BigInteger;
import java.util.Base64;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.POJONode;

/** Conversions between Jackson trees and openapi-fn {@link JsonValue}s. */
public final class JacksonJson {

    private JacksonJson() {
    }

    /** A Jackson tree as JSON; binary nodes become base64 strings, missing and POJO nodes {@code null}. */
    public static JsonValue toJsonValue(JsonNode node) {
        return switch (node.getNodeType()) {
            case OBJECT -> {
                var members = new LinkedHashMap<String, JsonValue>();
                node.properties().forEach(entry -> members.put(entry.getKey(), toJsonValue(entry.getValue())));
                yield new JsonObject(members);
            }
            case ARRAY -> new JsonArray(node.values().stream().map(JacksonJson::toJsonValue).toList());
            case STRING -> JsonString.of(node.stringValue());
            case NUMBER -> JsonNumber.of(node.decimalValue());
            case BOOLEAN -> JsonBoolean.of(node.booleanValue());
            case BINARY -> JsonString.of(Base64.getEncoder().encodeToString(node.binaryValue()));
            default -> JsonNull.INSTANCE;
        };
    }

    /** JSON as a Jackson tree. */
    public static JsonNode toJsonNode(JsonValue value) {
        var factory = JsonNodeFactory.instance;
        return switch (value) {
            case JsonObject object -> {
                var node = factory.objectNode();
                object.members().forEach((name, member) -> node.set(name, toJsonNode(member)));
                yield node;
            }
            case JsonArray array -> {
                var node = factory.arrayNode();
                array.values().forEach(item -> node.add(toJsonNode(item)));
                yield node;
            }
            case JsonString string -> factory.stringNode(string.value());
            case JsonNumber number -> number.isIntegral() ? integral(factory, number.value().toBigIntegerExact()) : factory.numberNode(number.value());
            case JsonBoolean bool -> factory.booleanNode(bool.value());
            case JsonNull ignored -> factory.nullNode();
        };
    }

    /** The smallest fitting integer node, so that untyped targets get {@code Integer} or {@code Long}. */
    private static JsonNode integral(JsonNodeFactory factory, BigInteger value) {
        if (value.bitLength() < Integer.SIZE) {
            return factory.numberNode(value.intValue());
        }
        return value.bitLength() < Long.SIZE ? factory.numberNode(value.longValue()) : factory.numberNode(value);
    }

    /** A tree node holding a Java object as-is (deserialized to that object when the target type matches). */
    public static JsonNode embed(Object value) {
        return new POJONode(value);
    }
}
