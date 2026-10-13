package dev.openapifn.spring.servlet;

import dev.openapifn.http.Multipart;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/** A servlet {@link Part} backed by a parsed multipart part. */
final class CachedPart implements Part {

    private final Multipart.Part part;

    CachedPart(Multipart.Part part) {
        this.part = part;
    }

    String text() {
        return new String(part.body(), StandardCharsets.UTF_8);
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(part.body());
    }

    @Override
    public @Nullable String getContentType() {
        return part.contentType().orElse(null);
    }

    @Override
    public String getName() {
        return part.name().orElse("");
    }

    @Override
    public @Nullable String getSubmittedFileName() {
        return part.filename().orElse(null);
    }

    @Override
    public long getSize() {
        return part.body().length;
    }

    @Override
    public void write(String fileName) throws IOException {
        Files.write(Path.of(fileName), part.body());
    }

    @Override
    public void delete() {
        // the content only lives in memory
    }

    @Override
    public @Nullable String getHeader(String name) {
        return part.headers().first(name).orElse(null);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return part.headers().all(name);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return part.headers().values().keySet();
    }
}
