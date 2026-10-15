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
    apply(plugin = "maven-publish")

    repositories {
        mavenCentral()
    }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<PublishingExtension> {
        // The Gradle plugin's publications come from java-gradle-plugin (the plugin and its marker).
        if (project.name != "openapi-fn-gradle-plugin") {
            publications.create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        // The plugin markers take their names and descriptions from java-gradle-plugin.
        publications.withType<MavenPublication>().matching { !it.name.endsWith("PluginMarkerMaven") }.configureEach {
            pom {
                name = project.name
                description = project.provider { project.description }
                url = "https://github.com/GenuineGuineapig/slop-openapi-suite"
                scm {
                    url = "https://github.com/GenuineGuineapig/slop-openapi-suite"
                    connection = "scm:git:https://github.com/GenuineGuineapig/slop-openapi-suite.git"
                }
            }
        }
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

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        options.locale = "en"
        // Every check but comments being required; any finding fails, as compiler warnings do.
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
        (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
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
        dependsOn(coverage, "javadoc")
    }
}
