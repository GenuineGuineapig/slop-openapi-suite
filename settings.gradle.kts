plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "openapi-fn"

include(
    "openapi-fn-validator",
    "openapi-fn-spring",
    "openapi-fn-spring-webflux",
    "openapi-fn-spring-test",
    "openapi-fn-generator",
    "openapi-fn-gradle-plugin",
)
