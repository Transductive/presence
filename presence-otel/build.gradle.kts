import java.util.UUID

plugins {
    `java-library`
    `maven-publish`
}

description = "Optional OpenTelemetry metrics for Presence Bloom filters."
apply(from = rootProject.file("gradle/library-conventions.gradle.kts"))

// Local workload and orchestration tools never enter the published library.
val smoke = sourceSets.create("smoke") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
    java.srcDir(rootProject.file("tools"))
    java.include("Telemetry.java", "io/transductive/presence/otel/smoke/**")
}
configurations[smoke.implementationConfigurationName].extendsFrom(configurations.implementation.get())
val smokeTest = sourceSets.create("smokeTest") {
    compileClasspath += smoke.output + sourceSets.main.get().output
    runtimeClasspath += smoke.output + sourceSets.main.get().output
}
configurations[smokeTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(), configurations[smoke.implementationConfigurationName]
)
configurations[smokeTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// Explicit local JMH tooling, excluded from build/check and the published adapter.
val benchmark = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[benchmark.implementationConfigurationName].extendsFrom(configurations.implementation.get())
val benchmarkTest = sourceSets.create("benchmarkTest") {
    compileClasspath += benchmark.output + sourceSets.main.get().output
    runtimeClasspath += benchmark.output + sourceSets.main.get().output
}
configurations[benchmarkTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(), configurations[benchmark.implementationConfigurationName]
)
configurations[benchmarkTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    api(project(":presence"))
    api(libs.otel.api)
    // The application supplies and owns its SDK, readers, and exporters.
    testImplementation(libs.otel.sdk.metrics)
    testImplementation(libs.otel.sdk.testing)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    add(smoke.implementationConfigurationName, libs.otel.sdk.metrics)
    add(smoke.implementationConfigurationName, libs.otel.exporter.otlp)
    add(smoke.implementationConfigurationName, "org.openjdk.jol:jol-core:0.17")
    add(benchmark.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(benchmark.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
    add(benchmark.implementationConfigurationName, libs.otel.sdk.metrics)
    add(benchmark.implementationConfigurationName, libs.otel.sdk.testing)
    add(benchmark.implementationConfigurationName, "io.pebbletemplates:pebble:4.1.2")
    add(benchmark.runtimeOnlyConfigurationName, "org.slf4j:slf4j-nop:2.0.18")
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.transductive.presence.otel") }
}

val smokeUnitTests = tasks.register<Test>("smokeTest") {
    description = "Test telemetry workload accounting and CLI validation without Docker or network services."
    group = "verification"
    testClassesDirs = smokeTest.output.classesDirs
    classpath = smokeTest.runtimeClasspath
    useJUnitPlatform()
}
tasks.check { dependsOn(smokeUnitTests) }

tasks.register<Test>("benchmarkTest") {
    description = "Verify OTel benchmark membership, SDK recording, and forced-refresh fixtures."
    group = "verification"
    testClassesDirs = benchmarkTest.output.classesDirs
    classpath = benchmarkTest.runtimeClasspath
    useJUnitPlatform()
}

fun registerOtelBenchmark(name: String, mode: String) = tasks.register<JavaExec>(name) {
    description = "Compare raw Bloom, no-op metrics, SDK recording, and operations that refresh statistics."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.otel.OtelBenchmarkRunner")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "512m"
    args(mode, rootProject.layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath)
    args(providers.gradleProperty("benchmarkSizes").getOrElse(if (mode == "smoke") "1000" else "1000,100000,1000000"))
}
registerOtelBenchmark("benchmark", "full")
registerOtelBenchmark("benchmarkSmoke", "smoke")

tasks.register<JavaExec>("telemetrySmoke") {
    description = "Run a paced OTLP workload against an already running local collector."
    group = "verification"
    classpath = smoke.runtimeClasspath
    mainClass.set("io.transductive.presence.otel.smoke.TelemetrySmoke")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "512m"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    args(providers.gradleProperty("smokeMinutes").getOrElse("1"))
    args(providers.gradleProperty("smokeRunId").getOrElse(UUID.randomUUID().toString()))
    args(providers.gradleProperty("smokeResult").getOrElse(
        rootProject.layout.buildDirectory.file("reports/telemetry/standalone-result.properties").get().asFile.absolutePath
    ))
}
