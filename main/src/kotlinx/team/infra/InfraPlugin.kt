package kotlinx.team.infra

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper
import org.semver4j.Semver
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

private const val REQUIRED_GRADLE_VERSION = "7.0"
private const val REQUIRED_KOTLIN_VERSION = "1.8.0"
private const val INFRA_EXTENSION_NAME = "infra"

@Suppress("unused")
class InfraPlugin : Plugin<Project> {

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
                logger.infra("Detected Kotlin plugin version '$pluginVersion'")
                if (Semver(pluginVersion) < Semver(REQUIRED_KOTLIN_VERSION))
                    throw KotlinInfrastructureException("JetBrains Kotlin Infrastructure plugin requires Kotlin version $REQUIRED_KOTLIN_VERSION or higher")
            }
        }
    }

    @Suppress("UnusedReceiverParameter")
    private fun Project.verifyGradleVersion() {
        if (GradleVersion.current() < GradleVersion.version(REQUIRED_GRADLE_VERSION))
            throw KotlinInfrastructureException("JetBrains Kotlin Infrastructure plugin requires Gradle version $REQUIRED_GRADLE_VERSION or higher")
    }
}

class KotlinInfrastructureException(message: String) : GradleException(message)