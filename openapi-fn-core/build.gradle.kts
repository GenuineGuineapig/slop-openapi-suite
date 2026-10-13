plugins {
    `java-library`
}

description = "OpenAPI 3.0/3.1/3.2 model, multi-document parser, JSON Schema and HTTP request/response validation"

dependencies {
    implementation(libs.snakeyaml.engine)
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
