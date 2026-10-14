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
            "com.example.shop.api.DefaultControllerTest", "com.example.shop.api.WebhooksControllerTest", "com.example.shop.api.CallbacksControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(10);
    }

    private static String resource(String name) throws java.io.IOException {
        try (var in = GeneratorTest.class.getResourceAsStream("/e2e/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void generatedClientsTalkToGeneratedControllers() throws Exception {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("shop", "com.example.shop").withTests(false), dir, Map.of(
            "com/example/shop/ShopApplication.java", APPLICATION,
            "com/example/shop/ShopService.java", resource("ShopService.java.txt"),
            "com/example/shop/EndToEnd.java", resource("EndToEnd.java.txt"),
            "com/example/shop/EndToEndIds.java", resource("EndToEndIds.java.txt")));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(compilation.classLoader);
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(compilation.loadClass("com.example.shop.ShopApplication"))
            .properties("server.port=0", "openapi-fn.validation.response.mode=FAIL")
            .run()) {
            var port = context.getEnvironment().getProperty("local.server.port");
            @SuppressWarnings("unchecked")
            var results = (java.util.List<String>) compilation.loadClass("com.example.shop.EndToEnd").getMethod("run", String.class)
                .invoke(null, "http://localhost:" + port + "/api/v1");
            assertThat(results).containsExactly(
                "list:3:[Cat/Tom, Dog/Rex]",
                "created:/pets/11:Dog/11",
                "problem:422:name must not be 'error'",
                "search:2",
                "get:Rex",
                "get404:true",
                "delete:204",
                "photo:image/png:PNG",
                "order:3-day:3:Card[method=card, last4=4242]:Address:[LinesItem[sku=sku-1, quantity=2]]",
                "pickup:StringValue[value=pickup]",
                "events:[placed, paid]",
                "health:UP",
                "adopted:202",
                "stock:204",
                "callback:204",
                "put:ack:hi",
                "invalid:400");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void edgeCasesCompileAndTheirTestsPass() {
        var api = Compilation.load("classpath:edge/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("edge", "org.example.edge"), dir, Map.of("org/example/edge/EdgeApplication.java", """
            package org.example.edge;

            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class EdgeApplication {
            }
            """));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        var summary = Launch.tests(compilation, "org.example.edge.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
    }

    @Test
    void variantsCompileAndTheirTestsPass() {
        var api = Compilation.load("classpath:variants/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("variants", "org.example.variants"), dir, Map.of(
            "org/example/variants/VariantsApplication.java", """
                package org.example.variants;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class VariantsApplication {
                }
                """));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        var summary = Launch.tests(compilation, "org.example.variants.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
    }
}
