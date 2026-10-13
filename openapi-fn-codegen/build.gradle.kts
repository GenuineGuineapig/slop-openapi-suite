plugins {
    `java-library`
}

description = "Generates Spring Boot 4 models, controllers, clients and controller tests from OpenAPI descriptions"

dependencies {
    api(project(":openapi-fn-core"))

    testImplementation(project(":openapi-fn-spring"))
    testImplementation(project(":openapi-fn-spring-test"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apiguardian:apiguardian-api:1.1.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
