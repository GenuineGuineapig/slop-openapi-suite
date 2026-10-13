package dev.openapifn.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorTest {

    @TempDir
    Path dir;

    @Test
    void generatedShopCompiles() {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("shop", "com.example.shop"), dir, Map.of("com/example/shop/ShopApplication.java", """
            package com.example.shop;

            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class ShopApplication {
            }
            """));
        if (System.getenv("KEEP") != null) {
            try {
                var keep = Path.of(System.getenv("KEEP"));
                for (var file : compilation.files) {
                    var target = keep.resolve(file.path());
                    java.nio.file.Files.createDirectories(target.getParent());
                    java.nio.file.Files.writeString(target, file.content());
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
    }

    static final String APPLICATION = """
        package com.example.shop;

        @org.springframework.boot.autoconfigure.SpringBootApplication
        public class ShopApplication {
        }
        """;

    @Test
    void generatedControllerTestsPass() throws Exception {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("shop", "com.example.shop"), dir, Map.of("com/example/shop/ShopApplication.java", APPLICATION));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        var summary = Launch.tests(compilation, "com.example.shop.api.PetsControllerTest", "com.example.shop.api.OrdersControllerTest",
            "com.example.shop.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(10);
    }
}
