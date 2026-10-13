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
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

openapiFn {
    specs {
        register("petstore") {
            spec = file("src/main/openapi/petstore.yaml")
            packageName = "com.example.petstore"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
}
