// Plugins are declared per module through the version catalog (gradle/libs.versions.toml).
tasks.register("clean", Delete::class) { delete(rootProject.layout.buildDirectory) }
