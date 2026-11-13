import java.security.MessageDigest
import java.net.URI

plugins { java }

group = "io.transductive"
version = "0.1.0-SNAPSHOT"
description = "Presence libraries and documentation."

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
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


// The root coordinates builds and website tools; only child projects publish libraries.
tasks.jar { enabled = false }
tasks.named("build") { dependsOn(":presence:build", ":presence-flink:build") }
tasks.named("check") { dependsOn(":presence:check", ":presence-flink:check") }
tasks.named("clean") { dependsOn(":presence:clean", ":presence-flink:clean") }

val coreJarFiles = configurations.create("coreJarFiles") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes { attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR)) }
}
dependencies { add(coreJarFiles.name, project(":presence")) }
val libraryJar = layout.file(providers.provider { coreJarFiles.singleFile })
val benchmarkLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

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
    val runnerJar = sitePlaygroundJar.flatMap { it.archiveFile }
    val libraryVersion = project.version.toString()
    dependsOn(coreJarFiles)
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
    runtimeClasspath += sitePlayground.output + siteTools.output + coreJarFiles
}
configurations[sitePlaygroundTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[sitePlaygroundTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
val verifySitePlayground = tasks.register<Test>("sitePlaygroundTest") {
    description = "Verify the website's compiler bridge and output limits."
    group = "verification"
    dependsOn(coreJarFiles)
    testClassesDirs = sitePlaygroundTest.output.classesDirs
    classpath = sitePlaygroundTest.runtimeClasspath
    systemProperty("presence.playground.jar", libraryJar.get().asFile.absolutePath)
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
    source = fileTree("presence/src/main/java") { include("**/*.java") }
    classpath = files()
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
