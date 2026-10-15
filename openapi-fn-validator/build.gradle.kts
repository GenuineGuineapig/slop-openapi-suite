plugins {
    `java-library`
}

description = "The runtime library: OpenAPI 3.0/3.1/3.2 model, multi-document parser, JSON Schema and HTTP request/response validation"

dependencies {
    implementation(libs.snakeyaml.engine)
    implementation(libs.jackson.core)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    // Example messages for comparing validation with other validators; the library itself does not depend on the generator.
    testImplementation(project(":openapi-fn-generator"))
    // The Atlassian validator, which openapi-fn replaces, as an independent judge of requests and responses.
    testImplementation(libs.atlassian.validator)
    // The JSON Schema validator Atlassian builds on, as an independent judge of bodies in the descriptions Atlassian cannot load.
    testImplementation(libs.networknt.validator)
}
