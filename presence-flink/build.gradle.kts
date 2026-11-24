import java.util.UUID

plugins {
    `java-library`
    `maven-publish`
}

description = "Keyed Java DataStream membership operators for Apache Flink 2.x."
apply(from = rootProject.file("gradle/library-conventions.gradle.kts"))

val flinkApiVersion = libs.versions.flink.get()
val flinkVersion = providers.gradleProperty("flinkVersion").getOrElse(flinkApiVersion)
require(flinkVersion.startsWith("2.")) { "Presence Flink requires Flink 2.x" }

dependencies {
    api(project(":presence"))
    // The consuming Flink job supplies its own runtime. Never bundle Flink.
    // Compile the published library against the API floor, even when testing newer runtimes.
    compileOnly("org.apache.flink:flink-streaming-java:$flinkApiVersion")
    testImplementation("org.apache.flink:flink-streaming-java:$flinkVersion")
    testImplementation("org.apache.flink:flink-runtime:$flinkVersion:tests")
    testImplementation("org.apache.flink:flink-test-utils:$flinkVersion")
    testImplementation("org.apache.flink:flink-statebackend-rocksdb:$flinkVersion")
    // Log4j's published class annotations must be present under -Xlint:all -Werror.
    testCompileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.0.0")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // The JUnit MiniCluster fixture runs two TaskManagers inside this test JVM.
    maxHeapSize = "1g"
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("integrationTest") {
    description = "Run a local Flink job to verify keyed routing and duplicate decisions."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "io.transductive.presence.flink") }
}

// A host-local MiniCluster and reporter are development tooling, never library dependencies.
val smoke = sourceSets.create("smoke") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
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
dependencies {
    // Keep the local runner/reporter on the API floor. flinkVersion selects library tests.
    add(smoke.implementationConfigurationName, "org.apache.flink:flink-streaming-java:$flinkApiVersion")
    add(smoke.implementationConfigurationName, "org.apache.flink:flink-clients:$flinkApiVersion")
    add(smoke.implementationConfigurationName, "org.apache.flink:flink-metrics-otel:$flinkApiVersion")
    add(smoke.runtimeOnlyConfigurationName, "org.apache.flink:flink-statebackend-rocksdb:$flinkApiVersion")
    // Flink 2.0's reporter POM marks its OTel dependencies optional. Match its SDK;
    // it uses internal SDK APIs, so the standalone adapter's newer SDK is unsuitable.
    add(smoke.runtimeOnlyConfigurationName, "io.opentelemetry:opentelemetry-sdk:1.30.0")
    add(smoke.runtimeOnlyConfigurationName, "io.opentelemetry:opentelemetry-exporter-otlp:1.30.0")
    add(smoke.runtimeOnlyConfigurationName, "io.opentelemetry:opentelemetry-semconv:1.30.0-alpha")
    add(smoke.runtimeOnlyConfigurationName, "org.slf4j:slf4j-simple:1.7.36")
}
val smokeUnitTests = tasks.register<Test>("smokeTest") {
    description = "Verify Flink smoke configuration and workload semantics without Docker or a running cluster."
    group = "verification"
    testClassesDirs = smokeTest.output.classesDirs
    classpath = smokeTest.runtimeClasspath
    useJUnitPlatform()
}
tasks.check { dependsOn(smokeUnitTests) }
tasks.register<JavaExec>("telemetrySmoke") {
    description = "Run a paced Flink MiniCluster job and export metrics to the local OTLP collector."
    group = "verification"
    classpath = smoke.runtimeClasspath
    mainClass.set("io.transductive.presence.flink.smoke.FlinkTelemetrySmoke")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "1g"
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    args(providers.gradleProperty("smokeMinutes").getOrElse("1"))
    args(providers.gradleProperty("smokeRunId").getOrElse(UUID.randomUUID().toString()))
    args(providers.gradleProperty("smokeResult").getOrElse(
        rootProject.layout.buildDirectory.file("reports/telemetry/flink-result.properties").get().asFile.absolutePath
    ))
}

val telemetrySmokeJar = tasks.register<Jar>("telemetrySmokeJar") {
    archiveClassifier.set("smoke")
    from(smoke.output)
}
tasks.register<Sync>("telemetrySmokeBundle") {
    description = "Assemble the local MiniCluster runner for Linux Docker; never published."
    group = "verification"
    from(telemetrySmokeJar, tasks.jar, configurations[smoke.runtimeClasspathConfigurationName])
    into(layout.buildDirectory.dir("telemetry-smoke/lib"))
}
