import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.security.MessageDigest
import java.net.URI

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
    add(benchmark.implementationConfigurationName, "io.pebbletemplates:pebble:4.1.2")
    add(benchmark.implementationConfigurationName, "com.google.code.gson:gson:2.15.0")
    add(benchmark.runtimeOnlyConfigurationName, "org.slf4j:slf4j-nop:2.0.18")
}

// Benchmark verification is explicit; regular build/check/ci run library tests only.
tasks.register<Test>("benchmarkTest") {
    description = "Verify benchmark fixtures and parameter validation without measuring performance."
    group = "verification"
    testClassesDirs = benchmarkTest.output.classesDirs
    classpath = benchmarkTest.runtimeClasspath
    // JOL's benchmark-only agent obtains object sizes from Instrumentation on Java 21.
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    useJUnitPlatform()
}

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
            if (mode == "smoke" || mode == "allocation") "1000"
            else if (mode == "scalable") "1000,10000,100000" else "1000,10000,100000,500000,1000000"
        ))
        args(providers.gradleProperty("benchmarkHits").getOrElse(
            when (mode) {
                "profile", "allocation" -> "50"
                "misses" -> "0"
                "prefilter" -> "0,50"
                "scalable" -> "0,100"
                else -> "0,50,100"
            }
        ))
        args(providers.gradleProperty("benchmarkInclude").getOrElse(
            when (mode) {
                "profile", "allocation" -> ".*\\.MembershipBenchmark\\..*"
                "misses" -> ".*\\.MembershipBenchmark\\.(bloomRapidhash|hashSet|hashMap)$"
                "prefilter" -> ".*PrefilterBenchmark.*"
                "scalable" -> ".*ScalableMembershipBenchmark.*"
                else -> ".*\\.(Membership|Construction)Benchmark\\..*"
            }
        ))
        args(providers.gradleProperty("benchmarkInputs").getOrElse(
            if (mode == "allocation") "ASCII,LATIN1,UNICODE,MALFORMED,LONG_UNICODE" else "ASCII"
        ))
        args(providers.gradleProperty("benchmarkWorkingSets").getOrElse(
            if (mode == "misses") "HOT,BROAD" else if (mode == "scalable") "BROAD" else "HOT"))
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
registerBenchmarkTask("benchmarkScalable", "scalable", "Compare fixed-size Bloom with growth from N=1000, including accuracy and payload size.")

// The large experiment deliberately owns a separate bounded fixture. Existing
// small/medium benchmarks retain their original datasets and defaults.
fun registerLargeBenchmarkTask(name: String, mode: String) = tasks.register<JavaExec>(name) {
    description = "Run the isolated large-N standard/blocked Bloom comparison and write an offline report."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.LargeBenchmarkRunner")
    javaLauncher.set(benchmarkLauncher)
    maxHeapSize = "512m"
    systemProperty("presence.benchmark.maxHeap", providers.gradleProperty("benchmarkMaxHeap").getOrElse("10g"))
    args(mode, layout.buildDirectory.dir("reports/benchmarks").get().asFile.absolutePath)
    args(providers.gradleProperty("benchmarkSizes").getOrElse(if (mode == "smoke") "1000" else "1000,10000000,50000000"))
    args(providers.gradleProperty("benchmarkHits").getOrElse("0"))
    args(providers.gradleProperty("benchmarkQueryModes").getOrElse("FRESH"))
    args(providers.gradleProperty("benchmarkQueryCount").getOrElse("1048576"))
    args(providers.gradleProperty("murmurBlockedBitsPerKey").getOrElse("9.89453125"))
    args(providers.gradleProperty("murmurBlockedHashCount").getOrElse("6"))
    args(providers.gradleProperty("rapidBlockedBitsPerKey").getOrElse("9.904296875"))
    args(providers.gradleProperty("rapidBlockedHashCount").getOrElse("6"))
    args(providers.gradleProperty("benchmarkInclude").getOrElse(".*LargeMembershipBenchmark.*"))
}

registerLargeBenchmarkTask("benchmarkLarge", "full")
registerLargeBenchmarkTask("benchmarkLargeSmoke", "smoke")
registerLargeBenchmarkTask("benchmarkLargeProfile", "profile")

tasks.register<JavaExec>("benchmarkReview") {
    description = "Render an offline review from saved large benchmark runs and optional accuracy/memory evidence."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.BenchmarkReview")
    javaLauncher.set(benchmarkLauncher)
    args(providers.gradleProperty("reviewManifest").getOrElse("benchmark-review.local.json"))
    args(layout.buildDirectory.dir("reports/benchmark-reviews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("benchmarkRenderProfiles") {
    description = "Rebuild io.transductive-only flamegraphs from a saved run's JFR recordings."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.ProfileRenderer")
    javaLauncher.set(benchmarkLauncher)
    args(providers.gradleProperty("profileRun").getOrElse(""))
}

tasks.register<JavaExec>("benchmarkLargeHeap") {
    description = "Measure retained large-fixture query pools separately from per-operation allocation."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.LargeQueryPoolReport")
    javaLauncher.set(benchmarkLauncher)
    maxHeapSize = "4g"
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    args(layout.buildDirectory.dir("reports/query-pools").get().asFile.absolutePath)
    args(providers.gradleProperty("benchmarkSizes").getOrElse("1000,10000000,50000000"))
    args(providers.gradleProperty("benchmarkHits").getOrElse("0"))
    args(providers.gradleProperty("benchmarkQueryCount").getOrElse("1048576"))
}

tasks.register<JavaExec>("benchmarkLargeAccuracy") {
    description = "Calibrate or independently validate blocked Bloom false-positive rates."
    group = "benchmark"
    classpath = benchmark.runtimeClasspath
    mainClass.set("io.transductive.presence.benchmarks.LargeAccuracyRunner")
    javaLauncher.set(benchmarkLauncher)
    maxHeapSize = "2g"
    systemProperty("presence.large.calibrationRound", providers.gradleProperty("calibrationRound").getOrElse("0"))
    val mode = providers.gradleProperty("accuracyMode").getOrElse("calibrate")
    val runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) +
        "-" + mode + "-" + UUID.randomUUID().toString().take(8)
    args(mode, layout.buildDirectory.dir("reports/large-accuracy/$runName").get().asFile.absolutePath)
    args(providers.gradleProperty("accuracySize").getOrElse(if (mode == "calibrate") "1000000" else "10000000"))
    args(providers.gradleProperty("accuracyMisses").getOrElse(if (mode == "calibrate") "8000000" else "2000000"))
    if (mode == "validate") {
        args(providers.gradleProperty("murmurBlockedBitsPerKey").getOrElse("9.89453125"))
        args(providers.gradleProperty("murmurBlockedHashCount").getOrElse("6"))
        args(providers.gradleProperty("rapidBlockedBitsPerKey").getOrElse("9.904296875"))
        args(providers.gradleProperty("rapidBlockedHashCount").getOrElse("6"))
    }
}

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

// Website tools stay outside the library and benchmark dependency graphs.
val sitePreview = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "siteServe" }
val sitePort = providers.gradleProperty("sitePort").getOrElse("8787")
val siteBaseUrl = providers.gradleProperty("siteBaseUrl").getOrElse(
    if (sitePreview) "http://127.0.0.1:$sitePort/" else "https://transductive.github.io/presence/"
)
val sitePages = layout.buildDirectory.dir("site-pages")
val siteOutput = layout.buildDirectory.dir("site")

val sitePlayground = sourceSets.create("sitePlayground") {
    java.setSrcDirs(listOf("site/playground/java"))
}
val siteTools = sourceSets.create("siteTools") {
    java.setSrcDirs(listOf("tools"))
    java.include("SiteServer.java")
}
val sitePlaygroundJar = tasks.register<Jar>("sitePlaygroundJar") {
    description = "Build the browser-only Java compiler bridge, outside the library artifact."
    archiveBaseName.set("presence-playground")
    from(sitePlayground.output)
}
fun fingerprint(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it) }.take(16)
val sitePlaygroundAssets = tasks.register<Sync>("sitePlaygroundAssets") {
    val libraryJar = tasks.jar.flatMap { it.archiveFile }
    val runnerJar = sitePlaygroundJar.flatMap { it.archiveFile }
    val libraryVersion = project.version.toString()
    inputs.property("version", libraryVersion)
    from(libraryJar) { rename { "presence-${fingerprint(libraryJar.get().asFile)}.jar" } }
    from(runnerJar) { rename { "playground-${fingerprint(runnerJar.get().asFile)}.jar" } }
    into(layout.buildDirectory.dir("site-playground"))
    doLast {
        destinationDir.resolve("manifest.json").writeText(
            """{"version":"$libraryVersion","library":"presence-${fingerprint(libraryJar.get().asFile)}.jar","runner":"playground-${fingerprint(runnerJar.get().asFile)}.jar"}"""
        )
    }
}

val sitePlaygroundTest = sourceSets.create("sitePlaygroundTest") {
    java.setSrcDirs(listOf("site/playground/test"))
    compileClasspath += sitePlayground.output + siteTools.output
    runtimeClasspath += sitePlayground.output + siteTools.output + sourceSets.main.get().output
}
configurations[sitePlaygroundTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[sitePlaygroundTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
val verifySitePlayground = tasks.register<Test>("sitePlaygroundTest") {
    description = "Verify the website's compiler bridge and output limits."
    group = "verification"
    dependsOn(tasks.jar)
    testClassesDirs = sitePlaygroundTest.output.classesDirs
    classpath = sitePlaygroundTest.runtimeClasspath
    systemProperty("presence.playground.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    inputs.file("site/static/playground/Playground.java")
    systemProperty("presence.playground.example", file("site/static/playground/Playground.java").absolutePath)
    inputs.file("site/static/playground/ScalablePlayground.java")
    systemProperty("presence.playground.scalableExample", file("site/static/playground/ScalablePlayground.java").absolutePath)
    useJUnitPlatform()
}

val siteGenerate = tasks.register<Exec>("siteGenerate") {
    group = "documentation"
    description = "Compile Markdown and DevLab templates with the mise-provisioned Zola."
    inputs.dir("site")
    inputs.property("baseUrl", siteBaseUrl)
    outputs.dir(sitePages)
    workingDir(projectDir)
    commandLine("zola", "--root", file("site").absolutePath, "build",
        "--base-url", siteBaseUrl, "--output-dir", sitePages.get().asFile.absolutePath, "--force")
    doFirst {
        // Zola replaces its output directory. Only allow this generated build path.
        check(sitePages.get().asFile.canonicalFile.toPath().startsWith(
            layout.buildDirectory.get().asFile.canonicalFile.toPath()))
    }
}

val siteJavadoc = tasks.register<Javadoc>("siteJavadoc") {
    group = "documentation"
    description = "Generate the website API reference from production Java sources."
    source = sourceSets.main.get().allJava
    classpath = sourceSets.main.get().compileClasspath
    destinationDir = layout.buildDirectory.dir("docs/site-javadoc").get().asFile
    inputs.file("site/javadoc/api-theme.css")
    (options as StandardJavadocDocletOptions).apply {
        windowTitle = "Presence API"
        docTitle = "Presence $version API"
        header = "<a href=\"{@docRoot}/../\">Presence / Field Guide</a>"
        addBooleanOption("notimestamp", true)
        addStringOption("-add-stylesheet", file("site/javadoc/api-theme.css").absolutePath)
    }
}

val siteBuild = tasks.register<Sync>("siteBuild") {
    group = "documentation"
    description = "Assemble the static website, playground JARs, Javadoc, licenses, and reference papers."
    dependsOn(siteGenerate, siteJavadoc)
    from(sitePages)
    from(sitePlaygroundAssets) { into("playground/lib") }
    from(siteJavadoc.map { it.destinationDir }) {
        into("api")
        // Some JDK 21 distributions reference DejaVu CSS without shipping it.
        // The website supplies its own local fonts through api-theme.css.
        filesMatching("stylesheet.css") {
            filter { line: String ->
                if (line.trim() == "@import url('resources/fonts/dejavu.css');")
                    "/* Font styling is supplied by Presence's api-theme.css. */" else line
            }
        }
    }
    from("resources/bloomfilters.pdf") { into("resources") }
    from("resources/scalable-bloom-filters.pdf") { into("resources") }
    from("LICENSE") { into("licenses"); rename { "presence-LICENSE.txt" } }
    from("site/themes/devlab-theme/LICENSE") { into("licenses"); rename { "devlab-LICENSE.txt" } }
    from("site/themes/devlab-theme/THIRD_PARTY_NOTICES.md") {
        into("licenses"); rename { "devlab-THIRD_PARTY_NOTICES.md" }
    }
    from("site/themes/devlab-theme/third_party") { into("licenses/third_party") }
    into(siteOutput)
    doLast { println("Website: ${siteOutput.get().asFile.toURI()}\nPreview: mise run site:serve") }
}

tasks.register<Exec>("siteCheck") {
    group = "verification"
    description = "Build and check local HTML links, fragments, and CSS assets."
    dependsOn(siteBuild, verifySitePlayground)
    executable = benchmarkLauncher.get().executablePath.asFile.absolutePath
    args(file("tools/SiteCheck.java").absolutePath, siteOutput.get().asFile.absolutePath, siteBaseUrl)
}

tasks.register<Exec>("siteServe") {
    group = "documentation"
    description = "Build and serve the complete site locally; restart after edits."
    dependsOn(siteBuild)
    executable = benchmarkLauncher.get().executablePath.asFile.absolutePath
    args(file("tools/SiteServer.java").absolutePath, siteOutput.get().asFile.absolutePath,
        sitePort, URI.create(siteBaseUrl).path)
}
