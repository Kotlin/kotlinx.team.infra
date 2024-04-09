import org.jetbrains.kotlin.samWithReceiver.gradle.SamWithReceiverExtension
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-sam-with-receiver:${project.extra["kotlinVersion"]}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${project.extra["kotlinVersion"]}")
    }
}

plugins {
    id("maven-publish")
    id("java-gradle-plugin")
}
apply(plugin = "org.jetbrains.kotlin.jvm")
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

configure<SamWithReceiverExtension> {
    annotation("org.gradle.api.HasImplicitReceiver")
}

project.version = project.findProperty("releaseVersion") ?: project.version

tasks.register<Jar>("sourceJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().java.sourceDirectories)
}

configure<PublishingExtension> {
    repositories {
        maven {
            name = "build"
            url = Path.of(rootProject.layout.buildDirectory.get().asFile.path, "maven").toUri()
        }
        maven {
            name = "space"
            url = URI.create("https://maven.pkg.jetbrains.space/kotlin/p/kotlinx/maven")
            credentials {
                username = project.findProperty("space.user") as? String
                password = project.findProperty("space.token") as? String
            }
        }
    }

    publications.configureEach {
        if (this is MavenPublication) artifact(tasks["sourceJar"])
    }
}

afterEvaluate {
    tasks {
        named<Delete>("clean") {
            publishing.repositories.forEach { repository ->
                if (repository is MavenArtifactRepository && repository.name == "build") {
                    delete.add(repository.url)
                }
            }
        }

        create("publishToBuildRepository") {
            group = "publishing"
            withType(PublishToMavenRepository::class.java) {
                if (repository.name == "build") {
                    this@create.dependsOn(this)
                }
            }
        }
    }
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib")
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk7")
    api("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    compileOnly("org.jetbrains.kotlin:kotlin-compiler")
    compileOnly("org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:${project.extra["kotlinVersion"]}")

    implementation("org.semver4j:semver4j:5.2.2")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

if (project.hasProperty("teamcity")) {
    gradle.taskGraph.whenReady {
        tasks.forEach { task ->
            task.doFirst { println("##teamcity[progressMessage 'Gradle: ${project.path}:${task.name}']") }
        }
    }
}
