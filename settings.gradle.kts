plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "openapi-fn"

include(
    "openapi-fn-core",
    "openapi-fn-spring",
    "openapi-fn-spring-test",
    "openapi-fn-codegen",
    "openapi-fn-gradle-plugin",
)
