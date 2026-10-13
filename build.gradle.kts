plugins {
    base
}

allprojects {
    group = "dev.openapifn"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    repositories {
        mavenCentral()
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withSourcesJar()
    }

    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testImplementation"(libs.findLibrary("assertj").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror", "-parameters"))
        options.isFork = true
        options.forkOptions.jvmArgs = listOf("-Duser.language=en", "-Duser.country=US")
    }

    tasks.withType<Jar>().configureEach {
        manifest.attributes("Automatic-Module-Name" to project.name.replace("openapi-fn-", "dev.openapifn.").replace('-', '.'))
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = libs.findVersion("jacoco").get().requiredVersion
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-Duser.language=en", "-Duser.country=US")
        maxHeapSize = "1g"
        finalizedBy("jacocoTestReport")
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required = true
            html.required = true
        }
    }

    val coverage = tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                listOf("INSTRUCTION", "BRANCH", "LINE", "METHOD").forEach { counterName ->
                    limit {
                        counter = counterName
                        value = "COVEREDRATIO"
                        minimum = "1.0".toBigDecimal()
                    }
                }
            }
        }
    }

    tasks.named("check") {
        dependsOn(coverage)
    }
}
