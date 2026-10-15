package dev.openapifn.http;

import dev.openapifn.http.SchemaShapes.Shape;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.Xml;
import java.util.Optional;

/**
 * Serializes JSON to XML guided by the schema and its XML Objects: element and attribute names,
 * namespaces and prefixes, wrapped and unwrapped arrays, and text nodes. The inverse of the XML
 * body parsing.
 */
public final class XmlWriter {

    private final SchemaShapes shapes;

    public XmlWriter(SchemaShapes shapes) {
        this.shapes = shapes;
    }

    /** {@code value} as an XML document whose root element is named by the schema or {@code defaultRoot}. */
    public String write(JsonValue value, Optional<Schema> schema, String defaultRoot) {
        var out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        var xml = schema.flatMap(this::xml);
        element(out, value, schema, xml.flatMap(Xml::name).orElse(defaultRoot), xml);
        return out.toString();
    }

    private void element(StringBuilder out, JsonValue value, Optional<Schema> schema, String name, Optional<Xml> xml) {
        var qualified = qualified(name, xml);
        out.append('<').append(qualified);
        xml.flatMap(Xml::namespace).ifPresent(ns -> out.append(xml.flatMap(Xml::prefix).map(p -> " xmlns:" + p).orElse(" xmlns"))
            .append("=\"").append(escape(ns)).append('"'));
        switch (value) {
            case JsonObject object -> object(out, object, schema, qualified);
            case JsonArray array -> {
                out.append('>');
                var items = schema.flatMap(shapes::items);
                var itemXml = items.flatMap(this::xml);
                var itemName = itemXml.flatMap(Xml::name).orElse(name);
                array.values().forEach(item -> element(out, item, items, itemName, itemXml));
                out.append("</").append(qualified).append('>');
            }
            default -> out.append('>').append(escape(ParameterSerializer.text(value))).append("</").append(qualified).append('>');
        }
    }

    private void object(StringBuilder out, JsonObject object, Optional<Schema> schema, String qualified) {
        var body = new StringBuilder();
        for (var member : object.members().entrySet()) {
            var property = schema.flatMap(s -> shapes.property(s, member.getKey()));
            var xml = property.flatMap(this::xml);
            var name = xml.flatMap(Xml::name).orElse(member.getKey());
            switch (xml.map(Xml::effectiveNodeType).orElse("element")) {
                case "attribute" -> out.append(' ').append(qualified(name, xml)).append("=\"")
                    .append(escape(ParameterSerializer.text(member.getValue()))).append('"');
                case "text" -> body.append(escape(ParameterSerializer.text(member.getValue())));
                case "cdata" -> body.append("<![CDATA[").append(ParameterSerializer.text(member.getValue()).replace("]]>", "]]]]><![CDATA[>"))
                    .append("]]>");
                default -> property(body, member.getValue(), property, name, xml);
            }
        }
        out.append('>').append(body).append("</").append(qualified).append('>');
    }

    private void property(StringBuilder out, JsonValue value, Optional<Schema> property, String name, Optional<Xml> xml) {
        if (value instanceof JsonArray array && property.map(shapes::shape).orElse(Shape.ARRAY) == Shape.ARRAY && !xml.filter(Xml::wrapped).isPresent()) {
            var items = property.flatMap(shapes::items);
            var itemXml = items.flatMap(this::xml);
            var itemName = itemXml.flatMap(Xml::name).orElse(name);
            array.values().forEach(item -> element(out, item, items, itemName, itemXml.or(() -> xml)));
            return;
        }
        element(out, value, property, name, xml);
    }

    private Optional<Xml> xml(Schema schema) {
        return shapes.closure(schema).stream().flatMap(s -> s.xml().stream()).findFirst();
    }

    private static String qualified(String name, Optional<Xml> xml) {
        return xml.flatMap(Xml::prefix).map(prefix -> prefix + ":" + name).orElse(name);
    }

    static String escape(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
