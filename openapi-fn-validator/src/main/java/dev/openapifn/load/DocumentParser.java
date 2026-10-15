package dev.openapifn.load;

import dev.openapifn.fn.Result;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonBoolean;
import dev.openapifn.json.JsonNull;
import dev.openapifn.json.JsonNumber;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonPointer;
import dev.openapifn.json.JsonString;
import dev.openapifn.json.JsonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Parses YAML 1.2 (core schema) and JSON documents into {@link JsonValue} trees with a
 * {@link SourceMap}. Mapping keys are always taken verbatim as strings (so {@code 200:} becomes
 * {@code "200"}), YAML merge keys ({@code <<}) are honoured, and duplicate and non-scalar keys
 * are rejected.
 */
public final class DocumentParser {

    private static final int CODE_POINT_LIMIT = 64 * 1024 * 1024;

    private final URI uri;
    private final Map<JsonPointer, Position> positions = new HashMap<>();
    private final Set<Node> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

    private DocumentParser(URI uri) {
        this.uri = uri;
    }

    /** Parses {@code content} as the document identified by {@code uri}. */
    public static Result<Document, Diagnostic> parse(URI uri, String content) {
        var settings = LoadSettings.builder()
            .setLabel(uri.toString())
            .setSchema(new CoreSchema())
            .setCodePointLimit(CODE_POINT_LIMIT)
            .setMaxAliasesForCollections(1000)
            .setAllowRecursiveKeys(false)
            .build();
        var parser = new DocumentParser(uri);
        try {
            var root = new Compose(settings).composeString(content)
                .map(node -> parser.convert(node, JsonPointer.ROOT))
                .orElse(JsonNull.INSTANCE);
            return Result.ok(new Document(uri, root, new SourceMap(parser.positions)));
        } catch (InvalidDocument e) {
            return Result.err(e.diagnostic);
        } catch (MarkedYamlEngineException e) {
            var diagnostic = Diagnostic.error("Invalid YAML: " + e.getProblem(), uri, JsonPointer.ROOT)
                .withPosition(e.getProblemMark().map(DocumentParser::position));
            return Result.err(diagnostic);
        } catch (YamlEngineException e) {
            return Result.err(Diagnostic.error("Invalid YAML: " + e.getMessage(), uri, JsonPointer.ROOT));
        }
    }

    private JsonValue convert(Node node, JsonPointer pointer) {
        node.getStartMark().ifPresent(mark -> positions.put(pointer, position(mark)));
        if (!inProgress.add(node)) {
            throw invalid("Recursive YAML alias", pointer, node);
        }
        try {
            return switch (node) {
                case ScalarNode scalar -> scalar(scalar);
                case SequenceNode sequence -> {
                    var values = new ArrayList<JsonValue>(sequence.getValue().size());
                    for (var item : sequence.getValue()) {
                        values.add(convert(item, pointer.child(values.size())));
                    }
                    yield new JsonArray(values);
                }
                default -> mapping((MappingNode) node, pointer);
            };
        } finally {
            inProgress.remove(node);
        }
    }

    private JsonObject mapping(MappingNode mapping, JsonPointer pointer) {
        var members = new LinkedHashMap<String, JsonValue>();
        for (NodeTuple tuple : mapping.getValue()) {
            var keyNode = (ScalarNode) tuple.getKeyNode();
            var key = keyNode.getValue();
            if (members.containsKey(key)) {
                throw invalid("Duplicate mapping key '" + key + "'", pointer.child(key), keyNode);
            }
            members.put(key, convert(tuple.getValueNode(), pointer.child(key)));
        }
        return new JsonObject(members);
    }

    private static JsonValue scalar(ScalarNode scalar) {
        var value = scalar.getValue();
        var tag = scalar.getTag();
        if (tag.equals(Tag.NULL)) {
            return JsonNull.INSTANCE;
        }
        if (tag.equals(Tag.BOOL)) {
            return JsonBoolean.of(value.equalsIgnoreCase("true"));
        }
        if (tag.equals(Tag.INT)) {
            return integer(value);
        }
        if (tag.equals(Tag.FLOAT)) {
            return decimal(value);
        }
        return JsonString.of(value);
    }

    private static JsonValue integer(String value) {
        if (value.startsWith("0x")) {
            return JsonNumber.of(new BigInteger(value.substring(2), 16));
        }
        if (value.startsWith("0o")) {
            return JsonNumber.of(new BigInteger(value.substring(2), 8));
        }
        return JsonNumber.of(new BigInteger(value));
    }

    private static JsonValue decimal(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".inf") || lower.equals(".nan")) {
            return JsonString.of(value);
        }
        return JsonNumber.of(new BigDecimal(value));
    }

    private static Position position(Mark mark) {
        return new Position(mark.getLine() + 1, mark.getColumn() + 1);
    }

    private InvalidDocument invalid(String message, JsonPointer pointer, Node node) {
        var diagnostic = Diagnostic.error(message, uri, pointer).withPosition(node.getStartMark().map(DocumentParser::position));
        return new InvalidDocument(diagnostic);
    }

    private static final class InvalidDocument extends RuntimeException {
        private final transient Diagnostic diagnostic;

        InvalidDocument(Diagnostic diagnostic) {
            super(diagnostic.message(), null, false, false);
            this.diagnostic = diagnostic;
        }
    }
}
