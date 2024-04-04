pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // TODO Re-introduce kotlin_version after upgrading Gradle
        id("org.jetbrains.kotlin.jvm") version "1.5.0"
    }
}
rootProject.name = "kotlinx.team.infra"
