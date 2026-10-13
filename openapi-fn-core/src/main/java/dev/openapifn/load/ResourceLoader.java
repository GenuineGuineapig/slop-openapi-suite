package dev.openapifn.load;

import dev.openapifn.fn.Result;
import java.net.URI;

/** Retrieves the text of a document. */
@FunctionalInterface
public interface ResourceLoader {

    /**
     * Loads the document at {@code uri} (which never has a fragment).
     *
     * @return the document text, or a human readable reason why it could not be loaded
     */
    Result<String, String> load(URI uri);
}
