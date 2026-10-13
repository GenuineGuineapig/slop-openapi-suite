pluginManagement {
    // Use the openapi-fn plugin from this repository; a real project would declare a version instead.
    includeBuild("../..")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "petstore"

// Substitutes the openapi-fn libraries the plugin adds with the projects of this repository.
includeBuild("../..")
