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
