package kotlinx.team.infra

import org.gradle.api.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.plugin.*
import java.io.*
import java.nio.file.*
import java.util.*

@Suppress("unused")
class InfraPlugin : Plugin<Project> {
    private val requiredGradleVersion = "6.0"
    private val requiredKotlinVersion = "1.4.0"
    private val INFRA_EXTENSION_NAME = "infra"

    override fun apply(target: Project) = target.run {
        verifyGradleVersion()
        verifyKotlinVersion()
        verifyRootProject()

        applyLocalProperties()

        val extension = installExtension()

        configureProjectVersion()
        
        // it only creates a task, so no problem with unpopulated extension
        configureTeamCityLogging()
        configureTeamCityConfigGenerator(extension.teamcity, extension.publishing)

        afterEvaluate {
            configureTeamcityBuildNumber(extension.teamcity)
        }
        extension.afterPublishing {
            configurePublishing(it)
        }

        configureNativeMultiplatform()

        subprojects {
            applyModule()
        }
    }

    private fun Project.applyModule() {
        applyVersionOverride()
    }
    
    private fun Project.applyVersionOverride() {
        if (project.version != rootProject.version) {
            logger.infra("Overriding subproject version to ${rootProject.version}")
            project.version = rootProject.version
        }
    }

    private fun Project.applyLocalProperties() {
        val path = "${project.rootDir}/local.properties"
        logger.infra("Loading additional properties from $path")
        if (Files.exists(Paths.get(path))) {
            val localProperties = FileInputStream(path).use {
                Properties().apply { load(it) }
            }

            localProperties.forEach { prop -> project.extensions.extraProperties.set(prop.key.toString(), prop.value) }
        }
    }

    private fun Project.installExtension(): InfraExtension {
        val extension = extensions.create(INFRA_EXTENSION_NAME, InfraExtension::class.java, project)

        return extension
    }

    private fun Project.verifyRootProject() {
        if (rootProject != this)
            throw KotlinInfrastructureException("JetBrains Kotlin Infrastructure plugin requires installation into root project")
    }

    private fun Project.verifyKotlinVersion() {
        val kotlinClass =
            tryGetClass<KotlinBasePluginWrapper>("org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper")
        if (kotlinClass != null) {
            plugins.findPlugin(kotlinClass)?.run {
                logger.infra("Detected Kotlin plugin version '$kotlinPluginVersion'")
                if (VersionNumber.parse(kotlinPluginVersion) < VersionNumber.parse(requiredKotlinVersion))
                    throw KotlinInfrastructureException("JetBrains Kotlin Infrastructure plugin requires Kotlin version $requiredKotlinVersion or higher")
            }
        }
    }

    private fun Project.verifyGradleVersion() {
        if (GradleVersion.current() < GradleVersion.version(requiredGradleVersion))
            throw KotlinInfrastructureException("JetBrains Kotlin Infrastructure plugin requires Gradle version $requiredGradleVersion or higher")
    }
}

class KotlinInfrastructureException(message: String) : GradleException(message)