package dev.openapifn.http;

import dev.openapifn.fn.Result;
import dev.openapifn.http.SchemaShapes.Shape;
import dev.openapifn.json.JsonArray;
import dev.openapifn.json.JsonObject;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import dev.openapifn.schema.Xml;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Converts XML documents to JSON guided by the schema and its XML Objects: element and
 * attribute names, wrapped and unwrapped arrays, and text nodes. Parsing is hardened against
 * external entities and DTDs.
 */
final class XmlBodies {

    private final SchemaShapes shapes;

    XmlBodies(SchemaShapes shapes) {
        this.shapes = shapes;
    }

    Result<JsonValue, String> parse(byte[] body, Optional<Schema> schema) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            var document = builder.parse(new ByteArrayInputStream(body));
            return Result.ok(element(document.getDocumentElement(), schema));
        } catch (Exception e) {
            return Result.err("must be well-formed XML: " + e.getMessage());
        }
    }

    private JsonValue element(Element element, Optional<Schema> schema) {
        var shape = schema.map(shapes::shape).orElse(children(element).isEmpty() ? Shape.PRIMITIVE : Shape.OBJECT);
        return switch (shape) {
            case PRIMITIVE -> shapes.coerce(element.getTextContent(), schema);
            case ARRAY -> {
                var items = schema.flatMap(shapes::items);
                yield new JsonArray(children(element).stream().map(child -> element(child, items)).toList());
            }
            case OBJECT -> object(element, schema);
        };
    }

    private JsonValue object(Element element, Optional<Schema> schema) {
        var members = new LinkedHashMap<String, JsonValue>();
        var claimed = new ArrayList<String>();
        var names = schema.map(shapes::propertyNames).orElse(java.util.Set.of());
        for (var name : names) {
            var property = shapes.property(schema.orElseThrow(), name).orElseThrow();
            var xml = xml(property);
            var xmlName = xml.flatMap(Xml::name).orElse(name);
            var nodeType = xml.map(Xml::effectiveNodeType).orElse("element");
            switch (nodeType) {
                case "attribute" -> {
                    if (element.hasAttribute(xmlName)) {
                        members.put(name, shapes.coerce(element.getAttribute(xmlName), Optional.of(property)));
                    }
                }
                case "text", "cdata" -> members.put(name, shapes.coerce(directText(element), Optional.of(property)));
                default -> elementProperty(element, name, xmlName, property, xml).ifPresent(value -> {
                    members.put(name, value);
                    claimed.add(xmlName);
                });
            }
        }
        for (var child : children(element)) {
            var local = localName(child);
            if (!claimed.contains(local) && !names.contains(local)) {
                members.putIfAbsent(local, element(child, schema.flatMap(s -> shapes.property(s, local))));
            }
        }
        return new JsonObject(members);
    }

    private Optional<JsonValue> elementProperty(Element parent, String name, String xmlName, Schema property, Optional<Xml> xml) {
        if (shapes.shape(property) != Shape.ARRAY) {
            return children(parent).stream().filter(child -> localName(child).equals(xmlName)).findFirst()
                .map(child -> element(child, Optional.of(property)));
        }
        var items = shapes.items(property);
        var itemName = items.flatMap(this::xml).flatMap(Xml::name).orElse(xmlName);
        var wrapped = xml.filter(Xml::wrapped).isPresent();
        var container = wrapped
            ? children(parent).stream().filter(child -> localName(child).equals(xmlName)).findFirst()
            : Optional.of(parent);
        return container.map(c -> new JsonArray(children(c).stream().filter(child -> localName(child).equals(itemName))
            .map(child -> element(child, items)).toList()));
    }

    private Optional<Xml> xml(Schema schema) {
        return shapes.closure(schema).stream().flatMap(s -> s.xml().stream()).findFirst();
    }

    private static String directText(Element element) {
        var text = new StringBuilder();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString().trim();
    }

    private static List<Element> children(Element element) {
        var result = new ArrayList<Element>();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                result.add(child);
            }
        }
        return result;
    }

    private static String localName(Element element) {
        return element.getLocalName();
    }
}
