plugins {
    `java-library`
}

description = "Spring WebFlux support: reactive runtime for generated controllers, WebClient clients, validation web filter, auto-configuration"

dependencies {
    api(project(":openapi-fn-spring"))
    api("org.springframework:spring-webflux")
    api("io.projectreactor:reactor-core")

    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
