pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "uv-sight-android"
include(":core")
// The Android module needs the Android SDK and the Android Gradle plugin.
// "-PskipAndroid" builds and tests the pure Kotlin core alone (e.g. without SDK).
if (!providers.gradleProperty("skipAndroid").isPresent) include(":app")
