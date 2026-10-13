package dev.openapifn.spring;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Binary content with optional file name and content type: the Java type of binary multipart
 * parts and binary bodies in generated code.
 *
 * @param filename the submitted file name, if any
 * @param contentType the media type of the content
 * @param content the bytes
 */
public record FilePart(@Nullable String filename, String contentType, byte[] content) {

    public static final String OCTET_STREAM = "application/octet-stream";

    public FilePart {
        Objects.requireNonNull(contentType, "contentType");
        content = content.clone();
    }

    public static FilePart of(byte[] content) {
        return new FilePart(null, OCTET_STREAM, content);
    }

    public static FilePart of(String filename, String contentType, byte[] content) {
        return new FilePart(filename, contentType, content);
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** The content decoded as UTF-8. */
    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public int size() {
        return content.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FilePart part && Objects.equals(filename, part.filename) && contentType.equals(part.contentType)
            && Arrays.equals(content, part.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filename, contentType, Arrays.hashCode(content));
    }

    @Override
    public String toString() {
        return "FilePart[filename=" + filename + ", contentType=" + contentType + ", size=" + content.length + "]";
    }
}
