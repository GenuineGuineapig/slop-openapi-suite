package dev.openapifn.schema;

import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import dev.openapifn.load.Uris;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable view of a schema at a location in a source document. Schemas are not
 * dereferenced: {@code $ref} stays visible (see {@link #ref()}) and is resolved through a
 * {@link SchemaRegistry}, which keeps recursive and cross-document schemas cheap and preserves
 * component names for code generation.
 *
 * @param json the schema: a {@link JsonObject}, or a {@link JsonBoolean} (JSON Schema 2019-09+)
 * @param document URI of the containing document
 * @param pointer location of the schema within the document
 * @param baseUri base URI for resolving references inside this schema
 * @param dialect the dialect this schema is interpreted with
 */
public record Schema(JsonValue json, URI document, JsonPointer pointer, URI baseUri, Dialect dialect) {

    public Schema {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(dialect, "dialect");
    }

    /** A schema rooted at {@code pointer} whose base URI is derived from its own identifier keyword. */
    public static Schema at(JsonValue json, URI document, JsonPointer pointer, URI parentBase, Dialect parentDialect) {
        var dialect = json instanceof JsonObject object
            ? object.string("$schema").flatMap(Dialect::fromUri).orElse(parentDialect)
            : parentDialect;
        return new Schema(json, document, pointer, rebase(json, parentBase, dialect), dialect);
    }

    private static URI rebase(JsonValue json, URI parentBase, Dialect dialect) {
        if (!(json instanceof JsonObject object)) {
            return parentBase;
        }
        return dialect.idKeyword()
            .flatMap(object::string)
            .flatMap(id -> Uris.resolve(parentBase, id).toOptional())
            .map(Uris::withoutFragment)
            .orElse(parentBase);
    }

    /** The absolute location of this schema: document URI plus pointer fragment. */
    public URI location() {
        return URI.create(document + pointer.toUriFragment());
    }

    /** The schema object, or an empty object for boolean schemas. */
    public JsonObject object() {
        return json instanceof JsonObject object ? object : JsonObject.EMPTY;
    }

    /** Whether this is the boolean schema {@code true} or {@code false}. */
    public boolean isBooleanSchema() {
        return json instanceof JsonBoolean;
    }

    /** Whether this schema accepts every instance by construction ({@code true} or {@code {}}). */
    public boolean acceptsEverything() {
        return json.equals(JsonBoolean.TRUE) || json.equals(JsonObject.EMPTY);
    }

    /** Whether this is the boolean schema {@code false}. */
    public boolean rejectsEverything() {
        return json.equals(JsonBoolean.FALSE);
    }

    public Optional<JsonValue> keyword(String name) {
        return object().get(name);
    }

    public boolean has(String name) {
        return object().has(name);
    }

    public Optional<String> ref() {
        return object().string("$ref");
    }

    public Optional<String> dynamicRef() {
        return object().string("$dynamicRef");
    }

    /** A schema-valued keyword such as {@code not}, {@code items} or {@code additionalProperties}. */
    public Optional<Schema> subschema(String keyword) {
        return keyword(keyword).filter(Schema::isSchemaValue).map(value -> child(value, pointer.child(keyword)));
    }

    /** A keyword holding a map of schemas such as {@code properties} or {@code $defs}. */
    public Map<String, Schema> subschemaMap(String keyword) {
        var result = new LinkedHashMap<String, Schema>();
        object().object(keyword).ifPresent(map -> map.members().forEach((name, value) -> {
            if (isSchemaValue(value)) {
                result.put(name, child(value, pointer.child(keyword).child(name)));
            }
        }));
        return result;
    }

    /** A keyword holding an array of schemas such as {@code allOf} or {@code prefixItems}. */
    public List<Schema> subschemaList(String keyword) {
        var result = new ArrayList<Schema>();
        var values = object().array(keyword).map(JsonArray::values).orElse(List.of());
        for (int i = 0; i < values.size(); i++) {
            if (isSchemaValue(values.get(i))) {
                result.add(child(values.get(i), pointer.child(keyword).child(i)));
            }
        }
        return result;
    }

    private Schema child(JsonValue value, JsonPointer childPointer) {
        return at(value, document, childPointer, baseUri, dialect);
    }

    private static boolean isSchemaValue(JsonValue value) {
        return value instanceof JsonObject || value instanceof JsonBoolean;
    }

    /** The declared types; empty means any type. Includes {@code "null"} for OpenAPI 3.0 {@code nullable}. */
    public Set<String> types() {
        var types = new LinkedHashSet<String>();
        keyword("type").ifPresent(type -> {
            switch (type) {
                case JsonString string -> types.add(string.value());
                case JsonArray array -> array.values().forEach(item -> item.asString().ifPresent(types::add));
                default -> {
                }
            }
        });
        if (!types.isEmpty() && dialect.nullableKeyword() && object().bool("nullable").orElse(false)) {
            types.add("null");
        }
        return types;
    }

    /** Whether {@code null} is an acceptable value according to the type declaration. */
    public boolean nullable() {
        return types().contains("null") || (dialect.nullableKeyword() && object().bool("nullable").orElse(false));
    }

    public List<String> required() {
        return stringList("required");
    }

    public Optional<String> format() {
        return object().string("format");
    }

    public Optional<String> title() {
        return object().string("title");
    }

    public Optional<String> description() {
        return object().string("description");
    }

    public Optional<String> pattern() {
        return object().string("pattern");
    }

    public List<JsonValue> enumValues() {
        return object().array("enum").map(JsonArray::values).orElse(List.of());
    }

    public Optional<JsonValue> constValue() {
        return keyword("const");
    }

    public Optional<JsonValue> defaultValue() {
        return keyword("default");
    }

    /** {@code examples} (2019-09+) followed by the OpenAPI {@code example}. */
    public List<JsonValue> examples() {
        var examples = new ArrayList<>(object().array("examples").map(JsonArray::values).orElse(List.of()));
        keyword("example").ifPresent(examples::add);
        return examples;
    }

    public boolean readOnly() {
        return object().bool("readOnly").orElse(false);
    }

    public boolean writeOnly() {
        return object().bool("writeOnly").orElse(false);
    }

    public boolean deprecated() {
        return object().bool("deprecated").orElse(false);
    }

    public Optional<BigDecimal> number(String keyword) {
        return object().number(keyword).map(JsonNumber::value);
    }

    public Optional<Discriminator> discriminator() {
        return object().object("discriminator").flatMap(d -> d.string("propertyName").map(name -> {
            var mapping = new LinkedHashMap<String, String>();
            d.object("mapping").ifPresent(m -> m.members().forEach((key, value) -> value.asString().ifPresent(v -> mapping.put(key, v))));
            return new Discriminator(name, mapping, d.string("defaultMapping"), extensions(d));
        }));
    }

    public Optional<Xml> xml() {
        return object().object("xml").map(x -> new Xml(x.string("name"), x.string("namespace"), x.string("prefix"),
            x.bool("attribute").orElse(false), x.bool("wrapped").orElse(false), x.string("nodeType"), extensions(x)));
    }

    /** Specification extensions ({@code x-*} keywords). */
    public Map<String, JsonValue> extensions() {
        return extensions(object());
    }

    /** The last pointer segment, which for component schemas is the component name. */
    public String name() {
        return pointer.last().orElse("");
    }

    private List<String> stringList(String keyword) {
        return object().array(keyword).map(array -> array.values().stream()
            .flatMap(value -> value.asString().stream()).toList()).orElse(List.of());
    }

    private static Map<String, JsonValue> extensions(JsonObject object) {
        var result = new LinkedHashMap<String, JsonValue>();
        object.members().forEach((key, value) -> {
            if (key.startsWith("x-")) {
                result.put(key, value);
            }
        });
        return result;
    }

    @Override
    public String toString() {
        return location().toString();
    }
}
