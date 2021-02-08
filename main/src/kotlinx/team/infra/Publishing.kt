package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.api.publish.*
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.*
import org.gradle.api.publish.maven.tasks.*
import org.gradle.api.publish.plugins.*
import org.gradle.api.tasks.*
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.gradle.plugins.signing.SigningPlugin
import org.gradle.util.*
import java.net.*
import java.text.*
import java.util.*

@Suppress("DEPRECATION")
open class PublishingConfiguration {
    var libraryRepoUrl: String? = null

    val sonatype = SonatypeConfiguration()
    fun sonatype(configure: Action<SonatypeConfiguration>) {
        configure.execute(sonatype)
        sonatype.isSelected = true
    }

    @Deprecated("Avoid publishing to bintray")
    val bintray = BintrayConfiguration()
    @Deprecated("Avoid publishing to bintray")
    fun bintray(configure: Action<BintrayConfiguration>) {
        configure.execute(bintray)
    }
    @Deprecated("Avoid publishing to bintray")
    fun bintray(configureClosure: Closure<BintrayConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, bintray)
    }

    @Deprecated("Avoid publishing to bintray")
    var bintrayDev: BintrayConfiguration? = null
    @Deprecated("Avoid publishing to bintray")
    fun bintrayDev(configure: Action<BintrayConfiguration>) {
        if (bintrayDev == null) bintrayDev = BintrayConfiguration()
        configure.execute(bintrayDev)
    }
    @Deprecated("Avoid publishing to bintray")
    fun bintrayDev(configureClosure: Closure<BintrayConfiguration>) {
        if (bintrayDev == null) bintrayDev = BintrayConfiguration()
        ConfigureUtil.configureSelf(configureClosure, bintrayDev)
    }

    var includeProjects: MutableList<String> = mutableListOf()
    fun include(vararg name: String) {
        includeProjects.addAll(name)
    }
}

class SonatypeConfiguration {
    // no things to configure here for now
    internal var isSelected: Boolean = false
}

// TODO: Add space configuration

// TODO: Remove all bintray-related configuration after migration
open class BintrayConfiguration {
    var username: String? = null
    var password: String? = null

    var organization: String? = null
    var repository: String? = null
    var library: String? = null

    var publish: Boolean = false
}

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

@Suppress("DEPRECATION")
internal fun Project.configurePublishing(publishing: PublishingConfiguration) {
    val ext = extensions.getByType(ExtraPropertiesExtension::class.java)
    
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
                subproject.configureSigning()
            }
        }
    } else {
        // If bintray is configured, create version task and configure subprojects
        val bintray = if (ext.get("infra.release") == true)
            publishing.bintray
        else
            publishing.bintrayDev ?: publishing.bintray

        val enableBintray = verifyBintrayConfiguration(bintray)
        if (enableBintray) {
            createBintrayVersionTask(bintray)
            includeProjects.forEach { subproject ->
                subproject.createBintrayRepository(bintray)
            }
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

    extensions.configure(PublishingExtension::class.java) { publishing ->
        val repo = publishing.repositories.maven { repo ->
            repo.name = name
            repo.setUrl(dir)
        }

        afterEvaluate {
            tasks.named("clean", Delete::class.java) {
                it.delete(dir)
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

private fun Project.createBintrayRepository(bintray: BintrayConfiguration) {
    val username = bintray.username
        ?: throw KotlinInfrastructureException("Cannot create version. User has not been specified.")
    val password = bintray.password
        ?: throw KotlinInfrastructureException("Cannot create version. Password (API key) has not been specified.")
    val publish = if (bintray.publish) "1" else "0"
    extensions.configure(PublishingExtension::class.java) { publishing ->
        publishing.repositories.maven { repo ->
            repo.name = "bintray"
            repo.url = URI("${bintray.api("maven")}/;publish=$publish")
            repo.credentials { credentials ->
                credentials.username = username
                credentials.password = password.trim()
            }
        }
    }
}

private fun BintrayConfiguration.api(section: String): String {
    val organization = organization
        ?: throw KotlinInfrastructureException("Cannot create version. Organization has not been specified.")
    val repository = repository
        ?: throw KotlinInfrastructureException("Cannot create version. Repository has not been specified.")
    val library = library
        ?: throw KotlinInfrastructureException("Cannot create version. Package has not been specified.")
    return "https://api.bintray.com/$section/$organization/$repository/$library"
}

private fun Project.createVersionPrepareTask(publishing: PublishingConfiguration): TaskProvider<DefaultTask> {
    return task<DefaultTask>("publishPrepareVersion") {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
        doFirst {
            val ext = extensions.getByType(ExtraPropertiesExtension::class.java)
            if (publishing.sonatype.isSelected && ext.get("infra.release") != true) {
                throw KotlinInfrastructureException("Cannot publish development version to Sonatype.")
            }
        }
    }
}

private fun Project.createBintrayVersionTask(bintray: BintrayConfiguration) {
    val bintrayCreateVersion = task<DefaultTask>("publishBintrayCreateVersion") {
        group = PublishingPlugin.PUBLISH_TASK_GROUP
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

            val url = URL("${bintray.api("packages")}/versions")
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
    tasks.named("publishPrepareVersion").configure {
        it.dependsOn(bintrayCreateVersion)
    }
}


private fun Project.verifySonatypeConfiguration(): Boolean {
    fun missing(what: String): Boolean {
        logger.warn("INFRA: Sonatype publishing will not be possible due to missing $what.")
        return false
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

    extensions.configure(PublishingExtension::class.java) { publishing ->
        publishing.repositories.maven { repo ->
            repo.name = "sonatype"
            repo.url = sonatypeRepositoryUri()
            repo.credentials { credentials ->
                credentials.username = username
                credentials.password = password.trim()
            }
        }
    }
}

private fun Project.configurePublications(publishing: PublishingConfiguration) {
    if (publishing.libraryRepoUrl.isNullOrEmpty()) {
        logger.warn("INFRA: library source control repository URL is not set, publication won't be accepted by Sonatype.")
    }
    val javadocJar = tasks.create("javadocJar", Jar::class.java).apply {
        archiveClassifier.set("javadoc")
    }
    extensions.configure(PublishingExtension::class.java) { publishingExtension ->
        publishingExtension.publications.all {
            with(it as MavenPublication) {
                artifact(javadocJar)
                configureRequiredPomAttributes(project, publishing)
            }
        }
    }
}

fun Project.mavenPublicationsPom(action: Action<MavenPom>) {
    extensions.configure(PublishingExtension::class.java) { publishingExtension ->
        publishingExtension.publications.all {
            action.execute((it as MavenPublication).pom)
        }
    }
}

private fun MavenPublication.configureRequiredPomAttributes(project: Project, publishing: PublishingConfiguration) {
    val publication = this
    // TODO: get rid of 'it's
    pom {
        it.name.set(publication.artifactId)
        it.description.set(project.description ?: publication.artifactId)
        it.url.set(publishing.libraryRepoUrl)
        it.licenses {
            it.license {
                it.name.set("The Apache License, Version 2.0")
                it.url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        it.scm {
            it.url.set(publishing.libraryRepoUrl)
        }
        it.developers {
            it.developer {
                it.name.set("JetBrains Team")
                it.organization.set("JetBrains")
                it.organizationUrl.set("https://www.jetbrains.com")
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
            it.useInMemoryPgpKeys(keyId, signingKey, signingKeyPassphrase)
            it.sign(extensions.getByType(PublishingExtension::class.java).publications) // all publications
        }
    } else {
        logger.warn("INFRA: signing key id is not specified, artifact signing is not enabled.")
    }
}


private fun Project.sonatypeRepositoryUri(): URI {
    val repositoryId: String? = System.getenv("libs.repository.id")
    return if (repositoryId == null) {
        // Using implicitly created staging, for MPP it's likely a mistake
        logger.warn("INFRA: using an implicitly created staging for ${project.rootProject.name}")
        URI("https://oss.sonatype.org/service/local/staging/deploy/maven2/")
    } else {
        URI("https://oss.sonatype.org/service/local/staging/deployByRepositoryId/$repositoryId")
    }
}

private val Project.sonatypeUsername: String? get() = propertyOrEnv("libs.sonatype.user")
private val Project.sonatypePassword: String? get() = propertyOrEnv("libs.sonatype.password")
