package kotlinx.team.infra

import org.gradle.api.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.*
import org.gradle.api.publish.*
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.*
import org.gradle.api.publish.maven.tasks.*
import org.gradle.api.publish.plugins.*
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin
import java.net.*
import javax.inject.Inject

open class PublishingConfiguration @Inject constructor(val objects: ObjectFactory) {
    var libraryRepoUrl: String? = null

    val sonatype = objects.newInstance<SonatypeConfiguration>()
    fun sonatype(configure: Action<SonatypeConfiguration>) {
        configure.execute(sonatype)
        sonatype.isSelected = true
    }

    var includeProjects: MutableList<String> = mutableListOf()
    fun include(vararg name: String) {
        includeProjects.addAll(name)
    }
}

open class SonatypeConfiguration {
    var libraryStagingRepoDescription: String? = null

    // other information is provided with properties or env. variables with known names:
    // - libs.repository.id: sonatype staging repository id, 'auto' to open staging implicitly,
    // - libs.sonatype.user: sonatype user name
    // - libs.sonatype.password: sonatype password
    // - libs.sign.key.id, libs.sign.key.private, libs.sign.passphrase: publication signing information
    internal var isSelected: Boolean = false
}

// TODO: Add space configuration


fun Project.configureProjectVersion() {
    val ext = extensions.getByType(ExtraPropertiesExtension::class.java)

    val releaseVersion = project.findProperty("releaseVersion")?.toString()
    project.version = if (releaseVersion != null && releaseVersion != "dev") {
        ext.set("infra.release", true)
        releaseVersion
    } else {
        ext.set("infra.release", false)
        // Configure version
        val versionSuffix = project.findProperty("versionSuffix")?.toString()
        if (versionSuffix != null && versionSuffix.isNotEmpty())
            "${project.version}-$versionSuffix"
        else
            project.version.toString()
    }

    logger.infra("Configured root project version as '${project.version}'")
}

internal fun Project.configurePublishing(publishing: PublishingConfiguration) {
    val buildLocal = "buildLocal"
    val compositeBuildLocal = "publishTo${buildLocal.capitalize()}"
    val rootBuildLocal = rootProject.tasks.maybeCreate(compositeBuildLocal).apply {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
    }

    // Apply maven-publish to all included projects
    val includeProjects = publishing.includeProjects.map { project(it) }
    logger.infra("Configuring projects for publishing: $includeProjects")
    includeProjects.forEach { subproject ->
        subproject.applyMavenPublish()
        subproject.createBuildRepository(buildLocal, rootBuildLocal)
        subproject.configurePublications(publishing)
    }

    createVersionPrepareTask(publishing)

    if (publishing.sonatype.isSelected) {
        if (verifySonatypeConfiguration()) {
            includeProjects.forEach { subproject ->
                subproject.createSonatypeRepository()
            }
        }
    }

    if (project.hasProperty("teamcity")) {
        includeProjects.forEach { subproject ->
            subproject.configureSigning()
        }
    }

    gradle.includedBuilds.forEach { includedBuild ->
        logger.infra("Included build: ${includedBuild.name} from ${includedBuild.projectDir}")
        val includedPublishTask = includedBuild.task(":$compositeBuildLocal")
        val includedName = includedBuild.name.split(".").joinToString(separator = "") { it.capitalize() }
        val copyIncluded = task<Copy>("copy${includedName}BuildLocal") {
            from(includedBuild.projectDir.resolve("build/maven"))
            into(buildDir.resolve("maven"))
            dependsOn(includedPublishTask)
        }
        rootBuildLocal.dependsOn(copyIncluded)

        val rootPublish = rootProject.tasks.maybeCreate("publish")
        rootPublish.dependsOn(includedBuild.task(":publish"))
    }
}

private fun Project.applyMavenPublish() {
    logger.infra("Enabling maven-publish plugin in $this")
    pluginManager.apply(MavenPublishPlugin::class.java)
}

private fun Project.createBuildRepository(name: String, rootBuildLocal: Task) {
    val dir = rootProject.buildDir.resolve("maven")

    logger.infra("Enabling publishing to $name in $this")
    val compositeTask = tasks.maybeCreate("publishTo${name.capitalize()}").apply {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        description = "Publishes all Maven publications produced by this project to Maven repository '$name'"
    }

    if (rootBuildLocal !== compositeTask)
        rootBuildLocal.dependsOn(compositeTask)

    extensions.configure(PublishingExtension::class.java) {
        val repo = repositories.maven {
            this.name = name
            this.setUrl(dir)
        }

        afterEvaluate {
            tasks.named("clean", Delete::class.java) {
                delete(dir)
            }

            compositeTask.dependsOn(
                tasks.withType(PublishToMavenRepository::class.java).matching { it.repository == repo }
            )
        }
    }
}

private fun Project.createVersionPrepareTask(publishing: PublishingConfiguration): TaskProvider<DefaultTask> {
    return task<DefaultTask>("publishPrepareVersion") {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        doFirst {
            val ext = project.extensions.getByType(ExtraPropertiesExtension::class.java)
            if (publishing.sonatype.isSelected && ext.get("infra.release") != true) {
                throw KotlinInfrastructureException("Cannot publish development version to Sonatype.")
            }
        }
    }
}


private fun Project.verifySonatypeConfiguration(): Boolean {
    fun missing(what: String): Boolean {
        logger.warn("INFRA: Sonatype publishing will not be possible due to missing $what.")
        return false
    }

    if (stagingRepositoryId.isNullOrEmpty()) {
        return missing("staging repository id 'libs.repository.id'. Pass 'auto' for implicit staging")
    }

    sonatypeUsername ?: return missing("username")
    val password = sonatypePassword ?: return missing("password")

    if (password.startsWith("credentialsJSON")) {
        logger.warn("INFRA: API key secure token was not expanded, publishing is not possible.")
        return false
    }

    if (password.trim() != password) {
        logger.warn("INFRA: API key secure token was expanded to a value with whitespace around it.")
    }

    if (password.trim().isEmpty()) {
        logger.warn("INFRA: API key secure token was expanded to empty string.")
    }
    return true
}

private fun Project.createSonatypeRepository() {
    val username = project.sonatypeUsername
        ?: throw KotlinInfrastructureException("Cannot setup publication. User has not been specified.")
    val password =  project.sonatypePassword
        ?: throw KotlinInfrastructureException("Cannot setup publication. Password (API key) has not been specified.")

    extensions.configure(PublishingExtension::class.java) {
        repositories.maven {
            name = "sonatype"
            url = sonatypeRepositoryUri()
            credentials {
                this.username = username
                this.password = password.trim()
            }
        }
    }
}

private fun Project.configurePublications(publishing: PublishingConfiguration) {
    if (publishing.libraryRepoUrl.isNullOrEmpty()) {
        logger.warn("INFRA: library source control repository URL is not set, publication won't be accepted by Sonatype.")
    }
    val javadocJar = tasks.register("javadocJar", Jar::class.java) {
        archiveClassifier.set("javadoc")
    }
    extensions.configure(PublishingExtension::class.java) {
        publications.configureEach {
            with(this as MavenPublication) {
                artifact(javadocJar)
                configureRequiredPomAttributes(project, publishing)
            }
        }
    }
}

fun Project.mavenPublicationsPom(action: Action<MavenPom>) {
    extensions.configure(PublishingExtension::class.java) {
        publications.configureEach {
            action.execute((this as MavenPublication).pom)
        }
    }
}

private fun MavenPublication.configureRequiredPomAttributes(project: Project, publishing: PublishingConfiguration) {
    val publication = this
    pom {
        name.set(publication.artifactId)
        description.set(project.description ?: publication.artifactId)
        url.set(publishing.libraryRepoUrl)
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        scm {
            url.set(publishing.libraryRepoUrl)
        }
        developers {
            developer {
                name.set("JetBrains Team")
                organization.set("JetBrains")
                organizationUrl.set("https://www.jetbrains.com")
            }
        }
    }
}

private fun Project.configureSigning() {
    project.pluginManager.apply(SigningPlugin::class.java)
    val keyId = project.propertyOrEnv("libs.sign.key.id")
    val signingKey = project.propertyOrEnv("libs.sign.key.private")
    val signingKeyPassphrase = project.propertyOrEnv("libs.sign.passphrase")

    if (keyId != null) {
        project.extensions.configure<SigningExtension>("signing") {
            useInMemoryPgpKeys(keyId, signingKey, signingKeyPassphrase)
            val signingTasks = sign(extensions.getByType(PublishingExtension::class.java).publications) // all publications
            // due to each publication including the same javadoc artifact file,
            // every publication signing task produces (overwrites) the same javadoc.asc signature file beside
            // and includes it to that publication
            // Thus, every publication publishing task implicitly depends on every signing task
            tasks.withType(AbstractPublishToMaven::class.java).configureEach {
                dependsOn(signingTasks) // make this dependency explicit
            }
        }
    } else {
        logger.warn("INFRA: signing key id is not specified, artifact signing is not enabled.")
    }
}


private fun Project.sonatypeRepositoryUri(): URI {
    val repositoryId: String? = stagingRepositoryId
    return when {
        repositoryId.isNullOrEmpty() ->
            throw KotlinInfrastructureException("Staging repository id 'libs.repository.id' is not specified.")
        repositoryId == "auto" -> {
            // Using implicitly created staging, for MPP it's likely a mistake
            logger.warn("INFRA: using an implicitly created staging for ${project.rootProject.name}")
            URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
        }
        else -> {
            URI("https://oss.sonatype.org/service/local/staging/deployByRepositoryId/$repositoryId")
        }
    }
}

private val Project.stagingRepositoryId: String? get() = propertyOrEnv("libs.repository.id")
private val Project.sonatypeUsername: String? get() = propertyOrEnv("libs.sonatype.user")
private val Project.sonatypePassword: String? get() = propertyOrEnv("libs.sonatype.password")
