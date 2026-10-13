plugins {
    `java-library`
}

description = "Spring Boot 4 integration: runtime for generated controllers and clients, validation filter, auto-configuration"

dependencies {
    api(project(":openapi-fn-core"))
    api(platform(libs.spring.boot.bom))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-webmvc")
    api("tools.jackson.core:jackson-databind")
    api("org.jspecify:jspecify")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.boot:spring-boot-jackson")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
