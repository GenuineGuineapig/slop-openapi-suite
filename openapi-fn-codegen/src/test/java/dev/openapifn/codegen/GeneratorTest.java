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
                "imported:2",
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
    void reactiveControllerTestsPass() {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("shop", "com.example.shop").withReactive(true), dir,
            Map.of("com/example/shop/ShopApplication.java", APPLICATION));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        assertThat(compilation.file("com/example/shop/api/PetsApi.java")).contains("Mono<GetPetResponse> getPet(GetPetRequest request);");
        assertThat(compilation.file("com/example/shop/api/OrderEventsResponse.java")).contains("Flux<OrderEvent> body");
        var summary = Launch.tests(compilation, "com.example.shop.api.PetsControllerTest", "com.example.shop.api.OrdersControllerTest",
            "com.example.shop.api.DefaultControllerTest", "com.example.shop.api.WebhooksControllerTest", "com.example.shop.api.CallbacksControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void reactiveClientsTalkToReactiveControllers() throws Exception {
        var api = Compilation.load("classpath:shop/openapi.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("shop", "com.example.shop").withTests(false).withReactive(true), dir, Map.of(
            "com/example/shop/ShopApplication.java", APPLICATION,
            "com/example/shop/ShopService.java", resource("ReactiveShopService.java.txt"),
            "com/example/shop/ReactiveEndToEnd.java", resource("ReactiveEndToEnd.java.txt"),
            "com/example/shop/EndToEndIds.java", resource("EndToEndIds.java.txt")));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(compilation.classLoader);
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(compilation.loadClass("com.example.shop.ShopApplication"))
            .web(org.springframework.boot.WebApplicationType.REACTIVE)
            .properties("server.port=0", "openapi-fn.validation.response.mode=FAIL")
            .run()) {
            var port = context.getEnvironment().getProperty("local.server.port");
            @SuppressWarnings("unchecked")
            var results = (java.util.List<String>) compilation.loadClass("com.example.shop.ReactiveEndToEnd").getMethod("run", String.class)
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
                "imported:2",
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
        var summary = Launch.tests(compilation, "org.example.variants.api.DefaultControllerTest", "org.example.variants.api.CallbacksControllerTest");
        assertThat(compilation.file("org/example/variants/api/GetPageResponse.java")).contains("Page<Point> body");
        // Inline records of a generic template share its type variable.
        assertThat(compilation.file("org/example/variants/model/Page.java")).contains("@Nullable Meta<T> meta", "record Meta<T>(", "@Nullable T first",
            "@Nullable Pages<T> pages", "record Pages<T>(", "@Nullable T last");
        assertThat(compilation.file("org/example/variants/api/CallbacksApi.java")).contains("PingResponse ping(PingRequest request);",
            "PongResponse pong();", "Ping2Response ping2();");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "edge, org.example.edge, EdgeApplication, org.example.edge.api.DefaultControllerTest",
        "variants, org.example.variants, VariantsApplication, org.example.variants.api.DefaultControllerTest org.example.variants.api.CallbacksControllerTest"})
    void reactiveVariantsCompileAndTheirTestsPass(String spec, String packageName, String applicationName, String tests) {
        // Spring's test bootstrapper caches the application class per package: the names match the Spring MVC runs.
        var api = Compilation.load("classpath:" + spec + "/openapi.yaml");
        var application = packageName.replace('.', '/') + "/" + applicationName + ".java";
        var compilation = Compilation.of(api, GeneratorConfig.of(spec, packageName).withReactive(true), dir, Map.of(application, """
            package %s;

            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class %s {
            }
            """.formatted(packageName, applicationName)));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        var summary = Launch.tests(compilation, tests.split(" "));
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isPositive();
    }

    @Test
    void remoteReferencesAreBundledAndServedFromTheBundle() {
        var remote = java.util.Map.of(
            java.net.URI.create("https://schemas.example.com/common/point.yaml"), """
                type: object
                required: [x, unit]
                properties:
                  x: {type: integer}
                  unit: {$ref: 'unit.yaml'}
                """,
            java.net.URI.create("https://schemas.example.com/common/unit.yaml"), "{type: string, enum: [mm, cm]}\n");
        var loader = new dev.openapifn.parse.OpenApiLoader(dev.openapifn.load.ResourceLoaders.withSchemes(dev.openapifn.load.ResourceLoaders.defaults(),
            java.util.Map.of("https", dev.openapifn.load.ResourceLoaders.inMemory(remote))));
        var api = loader.parse(dir.resolve("api.yaml").toUri(), """
            openapi: 3.1.0
            info: {title: Remote, version: 1.0.0}
            paths:
              /points:
                post:
                  operationId: addPoint
                  requestBody:
                    required: true
                    content:
                      application/json: {schema: {$ref: 'https://schemas.example.com/common/point.yaml'}}
                  responses:
                    '200':
                      description: the point
                      content:
                        application/json: {schema: {$ref: 'https://schemas.example.com/common/point.yaml'}}
            """).orElseThrow(diagnostics -> new AssertionError(diagnostics.toString()));
        var compilation = Compilation.of(api, GeneratorConfig.of("remote", "org.example.remote"), dir.resolve("out"), Map.of(
            "org/example/remote/RemoteApplication.java", """
                package org.example.remote;

                @org.springframework.boot.autoconfigure.SpringBootApplication
                public class RemoteApplication {
                }
                """));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.file("META-INF/openapi-fn/remote/spec.properties")).contains(
            "alias.0.uri=https://schemas.example.com/common/point.yaml\n",
            "alias.0.location=classpath:META-INF/openapi-fn/remote/remote/schemas.example.com/common/point.yaml\n",
            "alias.1.uri=https://schemas.example.com/common/unit.yaml\n");
        assertThat(compilation.file("META-INF/openapi-fn/remote/remote/schemas.example.com/common/unit.yaml")).contains("\"mm\"");
        var summary = Launch.tests(compilation, "org.example.remote.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isEqualTo(2);
    }

    @Test
    void swagger2DescriptionsAreConvertedAndGenerated() {
        var api = Compilation.load("classpath:swagger2/petstore.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("legacy", "org.example.legacy"), dir, Map.of("org/example/legacy/LegacyApplication.java", """
            package org.example.legacy;

            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class LegacyApplication {
            }
            """));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        assertThat(compilation.file("META-INF/openapi-fn/legacy/petstore.yaml")).contains("\"openapi\": \"3.0.3\"");
        var summary = Launch.tests(compilation, "org.example.legacy.api.PetsControllerTest", "org.example.legacy.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void multiDocumentSwagger2DescriptionsAreBundledAndGenerated() {
        var api = Compilation.load("classpath:swagger2/multi/api.yaml");
        var compilation = Compilation.of(api, GeneratorConfig.of("multi", "org.example.multi"), dir, Map.of("org/example/multi/MultiApplication.java", """
            package org.example.multi;

            @org.springframework.boot.autoconfigure.SpringBootApplication
            public class MultiApplication {
            }
            """));
        assertThat(compilation.success).as(compilation.diagnostics).isTrue();
        assertThat(compilation.diagnostics).as("warnings").isEmpty();
        assertThat(compilation.file("org/example/multi/model/Pet.java")).contains("List<Pet> children", "Owner owner", "Kind kind");
        var summary = Launch.tests(compilation, "org.example.multi.api.DefaultControllerTest");
        assertThat(summary.failures()).as(summary.describe()).isEmpty();
        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(3);
    }
}
