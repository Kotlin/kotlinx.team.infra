package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.publish.*
import org.gradle.api.publish.maven.plugins.*
import org.gradle.api.publish.maven.tasks.*
import org.gradle.api.publish.plugins.*
import org.gradle.api.tasks.*
import org.gradle.util.*
import java.net.*
import java.text.*
import java.util.*

open class PublishingConfiguration {
    val bintray = BintrayConfiguration()
    fun bintray(configureClosure: Closure<BintrayConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, bintray)
        bintrayHandler?.invoke(bintray)
    }

    private var bintrayHandler: ((BintrayConfiguration) -> Unit)? = null
    internal fun afterBintray(handler: (BintrayConfiguration) -> Unit) {
        bintrayHandler = handler
    }

    var includeProjects: MutableList<String> = mutableListOf()
    fun include(name: String) {
        includeProjects.add(name)
    }
}

open class BintrayConfiguration {
    var username: String? = null
    var password: String? = null

    var organization: String? = null
    var repository: String? = null
    var library: String? = null

    var publish: Boolean = false
}

fun Project.configurePublishing(publishing: PublishingConfiguration) {
    val buildLocal = "buildLocal"
    val compositeBuildLocal = "publishTo${buildLocal.capitalize()}"
    val rootBuildLocal = rootProject.tasks.maybeCreate(compositeBuildLocal)

    // Apply maven-publish to all included projects
    val includeProjects = publishing.includeProjects.map { project(it) }
    logger.infra("Configuring projects for publishing: $includeProjects")
    includeProjects.forEach { subproject ->
        subproject.applyMavenPublish()
        subproject.createBuildRepository(buildLocal, rootBuildLocal)
    }

    // If bintray is configured, create version task and configure subprojects
    val bintray = publishing.bintray
    val enableBintray = verifyBintrayConfiguration(bintray)
    if (enableBintray) {
        createBintrayVersionTask(bintray)
        includeProjects.forEach { subproject ->
            subproject.createBintrayRepository(bintray)
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

    extensions.configure(PublishingExtension::class.java) { publishing ->
        val repo = publishing.repositories.maven { repo ->
            repo.name = name
            repo.setUrl(dir)
        }

        afterEvaluate {
            tasks.named("clean", Delete::class.java) {
                delete(dir)
            }

            tasks.withType(PublishToMavenRepository::class.java) { task ->
                if (task.repository == repo) {
                    compositeTask.dependsOn(task)
                }
            }
        }
    }
}

private fun Project.verifyBintrayConfiguration(bintray: BintrayConfiguration): Boolean {
    fun missing(what: String): Boolean {
        logger.warn("INFRA: Bintray configuration is missing '$what', publishing will not be possible")
        return false
    }

    bintray.username ?: return missing("username")
    val password = bintray.password ?: return missing("password")
    if (password.startsWith("credentialsJSON")) {
        logger.warn("INFRA: API key secure token was not expanded, publishing is not possible")
        return false
    }

    if (password.trim() != password) {
        logger.warn("INFRA: API key secure token was expanded to a value with whitespace around it.")
    }

    if (password.trim().isEmpty()) {
        logger.warn("INFRA: API key secure token was expanded to empty string.")
    }

    val organization = bintray.organization ?: return missing("organization")
    val repository = bintray.repository ?: return missing("repository")
    val library = bintray.library ?: return missing("library")

    logger.infra("Enabling publishing to Bintray for package '$library' in '$organization/$repository' repository in $this")
    return true
}

fun Project.createBintrayRepository(bintray: BintrayConfiguration) {
    val username = bintray.username
        ?: throw KotlinInfrastructureException("Cannot create version. User has not been specified.")
    val password = bintray.password
        ?: throw KotlinInfrastructureException("Cannot create version. Password (API key) has not been specified.")
    val publish = if (bintray.publish) "1" else "0"
    extensions.configure(PublishingExtension::class.java) { publishing ->
        publishing.repositories.maven { repo ->
            repo.name = "bintray"
            repo.url = URI("${bintray.api()}/;publish=$publish")
            repo.credentials { credentials ->
                credentials.username = username
                credentials.password = password.trim()
            }
        }
    }
}

private fun BintrayConfiguration.api(): String {
    val organization = organization
        ?: throw KotlinInfrastructureException("Cannot create version. Organization has not been specified.")
    val repository = repository
        ?: throw KotlinInfrastructureException("Cannot create version. Repository has not been specified.")
    val library = library
        ?: throw KotlinInfrastructureException("Cannot create version. Package has not been specified.")
    return "https://api.bintray.com/packages/$organization/$repository/$library"
}

fun Project.createBintrayVersionTask(bintray: BintrayConfiguration) {
    task<DefaultTask>("publishBintrayCreateVersion") {
        doFirst {
            val username = bintray.username
                ?: throw KotlinInfrastructureException("Cannot create version. User has not been specified.")
            val password = bintray.password
                ?: throw KotlinInfrastructureException("Cannot create version. Password (API key) has not been specified.")
            val organization = bintray.organization
                ?: throw KotlinInfrastructureException("Cannot create version. Organization has not been specified.")
            val repository = bintray.repository
                ?: throw KotlinInfrastructureException("Cannot create version. Repository has not been specified.")
            val library = bintray.library
                ?: throw KotlinInfrastructureException("Cannot create version. Package has not been specified.")

            val url = URL("${bintray.api()}/versions")
            val now = Date()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
            val date = sdf.format(now)
            val versionJson = """{"name": "${project.version}", "desc": "", "released":"$date"}"""

            val basicAuthorization = "$username:$password"
            val encodedAuthorization = Base64.getEncoder().encodeToString(basicAuthorization.toByteArray())

            logger.lifecycle("Creating version ${project.version} for package $library in $organization/$repository on bintray…")
            logger.infra("URL: $url")
            logger.infra("User: $username")
            logger.infra("Sending: $versionJson")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Authorization", "Basic $encodedAuthorization");
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().use { it.write(versionJson) }
            }

            val code = connection.responseCode
            if (code >= 400) {
                val text = connection.errorStream.bufferedReader().readText()
                throw KotlinInfrastructureException("Cannot create version. HTTP response $code: $text")
            }
        }
    }
}