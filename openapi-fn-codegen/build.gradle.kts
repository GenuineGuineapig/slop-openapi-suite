plugins {
    `java-library`
}

description = "Generates Spring Boot 4 models, controllers, clients and controller tests from OpenAPI descriptions"

dependencies {
    api(project(":openapi-fn-core"))

    testImplementation(project(":openapi-fn-spring"))
    testImplementation(project(":openapi-fn-spring-test"))
    testImplementation(project(":openapi-fn-spring-webflux"))
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apiguardian:apiguardian-api:1.1.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

/** A self-contained jar for the command line: java -jar openapi-fn-codegen-<version>-cli.jar --spec ... */
val cliJar by tasks.registering(Jar::class) {
    archiveClassifier = "cli"
    manifest.attributes("Main-Class" to "dev.openapifn.codegen.cli.Main")
    from(sourceSets.main.map { it.output })
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.assemble {
    dependsOn(cliJar)
}
