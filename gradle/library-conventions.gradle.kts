import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions

group = rootProject.group
version = rootProject.version

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Werror", true)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from(rootProject.files("LICENSE", "NOTICE")) { into("META-INF") }
    from(rootProject.file("licenses")) { into("META-INF/licenses") }
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set(if (project.name == "presence") "Presence" else "Presence Flink")
                description.set(project.description)
                url.set("https://github.com/Transductive/presence")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                scm {
                    url.set("https://github.com/Transductive/presence")
                    connection.set("scm:git:https://github.com/Transductive/presence.git")
                }
            }
        }
    }
}
