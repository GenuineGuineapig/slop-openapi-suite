package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import dev.openapifn.schema.Schema;
import java.util.Map;

/** The Components Object; {@code mediaTypes} is new in 3.2. */
public record Components(Map<String, Schema> schemas, Map<String, Response> responses, Map<String, Parameter> parameters,
                         Map<String, Example> examples, Map<String, RequestBody> requestBodies, Map<String, Header> headers,
                         Map<String, SecurityScheme> securitySchemes, Map<String, Link> links, Map<String, Callback> callbacks,
                         Map<String, PathItem> pathItems, Map<String, MediaType> mediaTypes, Map<String, JsonValue> extensions) {

    public static final Components EMPTY = new Components(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of());

    public Components {
        schemas = Immutable.map(schemas);
        responses = Immutable.map(responses);
        parameters = Immutable.map(parameters);
        examples = Immutable.map(examples);
        requestBodies = Immutable.map(requestBodies);
        headers = Immutable.map(headers);
        securitySchemes = Immutable.map(securitySchemes);
        links = Immutable.map(links);
        callbacks = Immutable.map(callbacks);
        pathItems = Immutable.map(pathItems);
        mediaTypes = Immutable.map(mediaTypes);
        extensions = Immutable.map(extensions);
    }
}
