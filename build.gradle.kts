plugins {
    `java-library`
    `maven-publish`
}

group = "io.transductive"
version = "0.1.0-SNAPSHOT"
description = "Probabilistic membership checks in pure Java."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

// Benchmarks have their own dependency graph and never enter the published JAR.
val benchmark = sourceSets.create("benchmark") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val benchmarkTest = sourceSets.create("benchmarkTest") {
    compileClasspath += benchmark.output + sourceSets.main.get().output
    runtimeClasspath += benchmark.output + sourceSets.main.get().output
}
configurations[benchmarkTest.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(), configurations[benchmark.implementationConfigurationName]
)
configurations[benchmarkTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    add(benchmark.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
    add(benchmark.implementationConfigurationName, "org.openjdk.jol:jol-core:0.17")
    add(benchmark.annotationProcessorConfigurationName, "org.openjdk.jmh:jmh-generator-annprocess:1.37")
    add(benchmark.runtimeOnlyConfigurationName, "tools.profiler:jfr-converter:4.6")
}

val benchmarkTestTask = tasks.register<Test>("benchmarkTest") {
    description = "Verify benchmark fixtures and parameter validation without measuring performance."
    group = "verification"
    testClassesDirs = benchmarkTest.output.classesDirs
    classpath = benchmarkTest.runtimeClasspath
    // JOL's benchmark-only agent obtains object sizes from Instrumentation on Java 21.
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    useJUnitPlatform()
}
tasks.check { dependsOn(benchmarkTestTask) }

val benchmarkLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
fun registerBenchmarkTask(name: String, mode: String, taskDescription: String) =
    tasks.register<JavaExec>(name) {
        description = taskDescription
        group = "benchmark"
        classpath = benchmark.runtimeClasspath
        mainClass.set("io.transductive.presence.benchmarks.BenchmarkRunner")
        javaLauncher.set(benchmarkLauncher)
        systemProperty("presence.benchmark.maxHeap", providers.gradleProperty("benchmarkMaxHeap").getOrElse("512m"))
        args(mode, layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath)
        args(providers.gradleProperty("benchmarkSizes").getOrElse(
            if (mode == "smoke" || mode == "allocation") "1000" else "1000,10000,100000,500000,1000000"
        ))
        args(providers.gradleProperty("benchmarkHits").getOrElse(
            when (mode) {
                "profile", "allocation" -> "50"
                "misses" -> "0"
                "prefilter" -> "0,50"
                else -> "0,50,100"
            }
        ))
        args(providers.gradleProperty("benchmarkInclude").getOrElse(
            when (mode) {
                "profile", "allocation" -> ".*MembershipBenchmark.*"
                "misses" -> ".*MembershipBenchmark\\.(bloomRapidhash|hashSet|hashMap)$"
                "prefilter" -> ".*PrefilterBenchmark.*"
                else -> ".*(Membership|Construction)Benchmark.*"
            }
        ))
        args(providers.gradleProperty("benchmarkInputs").getOrElse(
            if (mode == "allocation") "ASCII,LATIN1,UNICODE,MALFORMED,LONG_UNICODE" else "ASCII"
        ))
        args(providers.gradleProperty("benchmarkWorkingSets").getOrElse(if (mode == "misses") "HOT,BROAD" else "HOT"))
        args(providers.gradleProperty("benchmarkQueryModes").getOrElse("CACHED"))
        args(providers.gradleProperty("benchmarkDownstreamTokens").getOrElse("0,256"))
        args(providers.gradleProperty("benchmarkStrategies").getOrElse("RAPIDHASH_V3"))
    }

registerBenchmarkTask("benchmark", "full", "Run JMH comparisons and generate a browser report.")
registerBenchmarkTask("benchmarkSmoke", "smoke", "Validate JMH wiring with a short run at N=1000.")
registerBenchmarkTask("benchmarkFlamegraph", "profile", "Record JFR profiles and generate HTML flamegraphs.")
registerBenchmarkTask("benchmarkAllocation", "allocation", "Measure lookup allocation across ASCII and Unicode inputs.")
registerBenchmarkTask("benchmarkMisses", "misses", "Compare absent lookups with hot and broad query working sets.")
registerBenchmarkTask("benchmarkPrefilter", "prefilter", "Compare exact downstream lookups with and without a Bloom guard.")

tasks.register<JavaExec>("benchmarkHeap") {
    description = "Measure reachable and incremental object graph footprints and generate an offline report."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.HeapReport")
    javaLauncher.set(benchmarkLauncher)
    maxHeapSize = "2g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    args(layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath)
    args(providers.gradleProperty("benchmarkSizes").getOrElse("1000,10000,100000,500000,1000000"))
    args(providers.gradleProperty("benchmarkInputs").getOrElse("ASCII"))
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

tasks.test {
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
    from(listOf("LICENSE", "NOTICE")) {
        into("META-INF")
    }
    from("licenses") {
        into("META-INF/licenses")
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.transductive.presence")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Presence")
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
