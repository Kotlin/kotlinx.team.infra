package kotlinx.team.infra

import org.gradle.api.*
import org.gradle.api.publish.*
import org.gradle.api.publish.maven.plugins.*
import org.gradle.api.publish.maven.tasks.*
import org.gradle.api.publish.plugins.*
import org.gradle.api.tasks.*
import java.net.*
import java.text.*
import java.util.*

fun Project.configurePublishing(publishing: PublishingConfiguration) {
    // Apply maven-publish to all included subprojects
    subprojects { subproject ->
        if (subproject.name in publishing.includeProjects) {
            subproject.applyMavenPublish()
            subproject.createBuildRepository("buildLocal")
        }
    }

    // If bintray is configured, create version task and configure subprojects
    val bintray = publishing.bintray
    val enableBintray = verifyBintrayConfiguration(bintray)
    if (enableBintray) {
        createBintrayVersionTask(bintray)
        subprojects { subproject ->
            if (subproject.name in publishing.includeProjects) {
                subproject.createBintrayRepository(bintray)
            }
        }
    }
}

private fun Project.applyMavenPublish() {
    logger.info("INFRA: Enabling maven-publish plugin in $this")
    pluginManager.apply(MavenPublishPlugin::class.java)
}

private fun Project.createBuildRepository(name: String) {
    val dir = rootProject.buildDir.resolve("maven")

    logger.info("INFRA: Enabling publishing to $name in $this")
    val compositeTask = task<DefaultTask>("publishTo${name.capitalize()}") {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        description = "Publishes all Maven publications produced by this project to Maven repository '$name'"
    }

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
                    compositeTask.get().dependsOn(task)
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
    bintray.password ?: return missing("password")

    val organization = bintray.organization ?: return missing("organization")
    val repository = bintray.repository ?: return missing("repository")
    val library = bintray.library ?: return missing("library")

    logger.info("INFRA: Enabling publishing to Bintray for package '$library' in '$organization/$repository' repository in $this")
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
                credentials.password = password
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
    return "https://api.bintray.com/maven/$organization/$repository/$library"
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

            val encodedAuthorization =
                java.util.Base64.getMimeEncoder().encode(("$username:$password").toByteArray())

            logger.lifecycle("INFRA: Creating version ${project.version} for package $library in $organization/$repository on bintray…")
            logger.info("INFRA: User: $username")
            logger.info("INFRA: Sending: $versionJson")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                doOutput = true
                requestMethod = "POST"
                setRequestProperty("Authorization", "Basic $encodedAuthorization");
                setRequestProperty("Content-Type", "application/json")
                outputStream.bufferedWriter().write(versionJson)
            }

            val code = connection.responseCode
            if (code >= 400) {
                val text = connection.errorStream.bufferedReader().readText()
                throw KotlinInfrastructureException("Cannot create version. HTTP response $code: $text")
            }
        }
    }
}