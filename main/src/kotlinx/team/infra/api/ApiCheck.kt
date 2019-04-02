package kotlinx.team.infra.api;

import kotlinx.team.infra.*
import kotlinx.team.infra.api.js.*
import kotlinx.team.infra.api.jvm.*
import kotlinx.team.infra.api.native.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*

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

fun Project.configureTargetApiCheck(target: KotlinTarget, checkApiTask: Task) {
    val targetName = target.name
    val mainCompilation = target.compilations.findByName("main") ?: run {
        logger.infra("Cannot find 'main' compilation of $target in $this")
        return
    }

    logger.infra("Configuring $target of $this for API check")
    val apiBuildDir = file(buildDir.resolve(buildDir).resolve("apiCheck").resolve(targetName))

    val targetCheckApiTask = when (target.platformType) {
        KotlinPlatformType.common -> null
        KotlinPlatformType.jvm, KotlinPlatformType.androidJvm -> createJvmApiCheckTask(target, mainCompilation, apiBuildDir)
        KotlinPlatformType.js -> createJsApiCheckTask(target, mainCompilation, apiBuildDir)
        KotlinPlatformType.native -> createNativeApiCheckTask(target, mainCompilation, apiBuildDir)
    }

    if (targetCheckApiTask != null)
        checkApiTask.dependsOn(targetCheckApiTask)
}
