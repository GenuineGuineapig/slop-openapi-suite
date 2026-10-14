package dev.openapifn.spring.webflux.app;

import dev.openapifn.spring.FilePart;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Upload(FilePart file, @Nullable List<FilePart> extra, @Nullable String caption) {
}
