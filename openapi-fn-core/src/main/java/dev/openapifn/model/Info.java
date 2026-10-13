package dev.openapifn.model;

import dev.openapifn.fn.Immutable;
import dev.openapifn.json.JsonValue;
import java.util.Map;
import java.util.Optional;

/** The Info Object. */
public record Info(String title, Optional<String> summary, Optional<String> description, Optional<String> termsOfService,
                   Optional<Contact> contact, Optional<License> license, String version, Map<String, JsonValue> extensions) {

    public Info {
        extensions = Immutable.map(extensions);
    }
}
