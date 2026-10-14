plugins {
    `java-library`
}

description = "Test support: MockMvc matchers and WebTestClient assertions that check requests and responses against the API description"

dependencies {
    api(project(":openapi-fn-spring"))
    api("org.springframework:spring-test")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-webflux")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework:spring-webflux")
}
