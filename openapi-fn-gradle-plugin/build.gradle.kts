plugins {
    `java-gradle-plugin`
}

description = "Gradle plugin: generates models, controllers, clients and controller tests from OpenAPI descriptions during the build"

dependencies {
    implementation(project(":openapi-fn-generator"))
}

gradlePlugin {
    plugins {
        create("openapiFn") {
            id = "dev.openapifn"
            implementationClass = "dev.openapifn.gradle.OpenApiFnPlugin"
            displayName = "openapi-fn"
            description = project.description
        }
    }
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/plugin.properties") {
        expand("version" to version)
    }
}
