package dev.openapifn.spring.webflux.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pet(@Nullable Long id, String name, @Nullable String tag) {
}
