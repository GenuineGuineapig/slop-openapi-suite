plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("dev.openapifn")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webclient")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

openapiFn {
    // Spring WebFlux: Mono/Flux interfaces, reactive controllers, WebClient clients and WebTestClient tests.
    reactive = true
    specs {
        register("train") {
            spec = file("src/main/openapi/openapi.yaml")
            packageName = "com.example.train"
            // The first server is a hosted mock; serve the API at the root like the production server.
            basePath = "/"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
}
