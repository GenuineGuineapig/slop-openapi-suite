plugins {
    `java-library`
}

description = "OpenAPI 3.0/3.1/3.2 model, multi-document parser, JSON Schema and HTTP request/response validation"

dependencies {
    implementation(libs.snakeyaml.engine)
    implementation(libs.jackson.core)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    // The Atlassian validator, which openapi-fn replaces, as an independent judge of requests and responses.
    testImplementation(libs.atlassian.validator)
}
