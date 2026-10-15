plugins {
    `java-library`
}

description = "Spring Boot 4 integration: runtime for generated controllers and clients, Spring MVC support (validation filter), auto-configuration"

dependencies {
    api(project(":openapi-fn-validator"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-web")
    api("tools.jackson.core:jackson-databind")
    api("org.jspecify:jspecify")
    // Spring MVC applications bring these (spring-boot-starter-webmvc); WebFlux applications must not get them.
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.boot:spring-boot-jackson")
    // With Spring Security, credentials are verified by what it authenticated a request as.
    compileOnly("org.springframework.security:spring-security-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.springframework.security:spring-security-core")
}
