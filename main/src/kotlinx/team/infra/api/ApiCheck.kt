package kotlinx.team.infra.api;

import kotlinx.team.infra.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*

public class ApiCheckConfiguration {
    var includeProjects: MutableList<String> = mutableListOf()
    fun include(vararg name: String) {
        includeProjects.addAll(name)
    }
}

fun Project.configureApiCheck(apiCheck: ApiCheckConfiguration) {
    val multiplatformClass =
        tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")

    val includeProjects = apiCheck.includeProjects.map { project(it) }
    logger.infra("Configuring projects for API check: $includeProjects")

    includeProjects.forEach { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val multiplatform = multiplatformClass?.let { subproject.extensions.findByType(it) }
            if (multiplatform == null) {
                logger.infra("Skipping apiCheck configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            val apiCheckTask = subproject.tasks.create("checkApi") {
                it.group = "verification"
                it.description = "Runs API checks for 'main' compilations of all targets"
            }

            subproject.tasks.getByName("check").dependsOn(apiCheckTask)

            multiplatform.targets.all { target ->
                subproject.configureTargetApiCheck(target, apiCheckTask)
            }
        }
    }
}

fun Project.configureTargetApiCheck(target: KotlinTarget, checkApi: Task) {
    val targetName = target.name
    val mainCompilation = target.compilations.findByName("main") ?: run {
        logger.infra("Cannot find 'main' compilation in $target in $this")
        return
    }

    logger.infra("Configuring $target in $this for API check")
    val apiBuildDir = file(buildDir.resolve(buildDir).resolve("apiCheck").resolve(targetName))

    val targetCheckApi = task<ApiCheckTask>("${targetName}CheckApi") {
        group = "verification"
        description = "Runs API checks for 'main' compilation of target '${target.name}'"

        this.platformType = target.platformType
        nativeTarget = (target as? KotlinNativeTarget)?.konanTarget?.name ?: ""
        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir

        doFirst {
            apiBuildDir.mkdirs()
        }
    }
    checkApi.dependsOn(targetCheckApi)
}

