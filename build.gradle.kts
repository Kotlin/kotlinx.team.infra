import org.jetbrains.kotlin.samWithReceiver.gradle.SamWithReceiverExtension
import java.nio.file.Files
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // TODO Re-introduce kotlin_version after upgrading Gradle
        classpath("org.jetbrains.kotlin:kotlin-sam-with-receiver:1.5.0")
    }
}

plugins {
    id("maven-publish")
    id("java-gradle-plugin")
    id("org.jetbrains.kotlin.jvm")
}
apply(plugin = "kotlin-sam-with-receiver")

// Load `local.properties` file, if it exists. You can put your spaceUser and spaceToken values there, that file is ignored by git
val localPropertiesFile = File("$project.rootDir/local.properties")
if (Files.exists(localPropertiesFile.toPath())) {
    localPropertiesFile.reader().use { reader ->
        val localProperties = Properties()
        localProperties.load(reader)
        localProperties.forEach { (key, value) -> project.ext.set(key.toString(), value) }
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

configure<GradlePluginDevelopmentExtension> {
    plugins {
        create("teamInfraPlugin") {
            id = "kotlinx.team.infra"
            implementationClass = "kotlinx.team.infra.InfraPlugin"
        }
    }
}

sourceSets {
    getByName("main") {
        java.srcDirs("main/src")
        resources.srcDirs("main/resources")
    }
    getByName("test") {
        java.srcDirs("test/src")
        resources.srcDirs("test/resources")
    }
}

dependencies {
    val kotlin_version = "1.5.0"
    api("org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version")
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlin_version")
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")

    compileOnly("org.jetbrains.kotlin:kotlin-compiler:$kotlin_version")
    compileOnly("org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:$kotlin_version")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.12")
}

configure<SamWithReceiverExtension> {
    annotation("org.gradle.api.HasImplicitReceiver")
}

apply(from = "publish.gradle")

if (project.hasProperty("teamcity")) {
    gradle.taskGraph.beforeTask {
        println("##teamcity[progressMessage 'Gradle: ${project.path}:$name']")
    }
}
