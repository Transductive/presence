// The build coordinator must not share the core module's Gradle component identity.
rootProject.name = "presence-build"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include("presence", "presence-flink")
