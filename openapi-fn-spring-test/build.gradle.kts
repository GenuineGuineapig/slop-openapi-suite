plugins {
    `java-library`
}

description = "Test support: MockMvc matchers that check requests and responses against the API description"

dependencies {
    api(project(":openapi-fn-spring"))
    api("org.springframework:spring-test")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
}
