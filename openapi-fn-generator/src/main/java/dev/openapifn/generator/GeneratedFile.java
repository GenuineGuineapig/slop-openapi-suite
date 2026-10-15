package dev.openapifn.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A generated file.
 *
 * @param kind which output root it belongs to
 * @param path the path relative to that root, using {@code /}
 * @param content the content
 */
public record GeneratedFile(Kind kind, String path, String content) {

    /** Output roots. */
    public enum Kind {
        SOURCE,
        TEST_SOURCE,
        RESOURCE
    }

    public GeneratedFile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
    }

    /** Writes {@code files} below the root of their kind. */
    public static void write(List<GeneratedFile> files, Path sources, Path tests, Path resources) throws IOException {
        for (var file : files) {
            var root = switch (file.kind()) {
                case SOURCE -> sources;
                case TEST_SOURCE -> tests;
                case RESOURCE -> resources;
            };
            var target = root.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }
}
