plugins {
    `java-library`
    `maven-publish`
}

description = "Optional OpenTelemetry metrics for Presence Bloom filters."
apply(from = rootProject.file("gradle/library-conventions.gradle.kts"))

dependencies {
    api(project(":presence"))
    api(libs.otel.api)
    // The application supplies and owns its SDK, readers, and exporters.
    testImplementation(libs.otel.sdk.metrics)
    testImplementation(libs.otel.sdk.testing)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.transductive.presence.otel") }
}
