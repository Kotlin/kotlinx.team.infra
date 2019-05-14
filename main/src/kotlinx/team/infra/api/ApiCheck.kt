package kotlinx.team.infra.api

import kotlinx.team.infra.*
import kotlinx.team.infra.api.js.*
import kotlinx.team.infra.api.jvm.*
import kotlinx.team.infra.api.native.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*

class ApiCheckConfiguration {
    var includeProjects: MutableList<String> = mutableListOf()
    fun include(vararg name: String) {
        includeProjects.addAll(name)
    }

    var apiDir = "api"
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
            
            val apiPublishTask = subproject.tasks.create("publishApiToBuildLocal") {
                it.group = "publishing"
                it.description = "Publishes API for 'main' compilations of all targets into local build folder"
            }
            tasks.findByName("publishToBuildLocal")?.dependsOn(apiPublishTask)

            val apiSyncTask = subproject.tasks.create("syncApi") {
                it.group = "other"
                it.description = "Syncs API for 'main' compilations of all targets"
            }

            subproject.tasks.getByName("check").dependsOn(apiCheckTask)

            multiplatform.targets.all { target ->
                subproject.configureTargetApiCheck(target, apiCheckTask, apiSyncTask, apiPublishTask, apiCheck)
            }
        }
    }
}

fun Project.configureTargetApiCheck(
    target: KotlinTarget,
    checkApiTask: Task,
    syncApiTask: Task,
    publishLocalTask: Task,
    apiCheck: ApiCheckConfiguration
) {
    val project = this
    val targetName = target.name
    val mainCompilation = target.compilations.findByName("main") ?: run {
        logger.infra("Cannot find 'main' compilation of $target in $this")
        return
    }

    logger.infra("Configuring $target of $this for API check")
    val apiBuildDir = file(buildDir.resolve(apiCheck.apiDir).resolve(targetName))
    val apiCheckDir = file(projectDir.resolve(apiCheck.apiDir).resolve(targetName))

    val targetBuildApiTask = when (target.platformType) {
        KotlinPlatformType.common -> null
        KotlinPlatformType.jvm,
        KotlinPlatformType.androidJvm -> createJvmApiBuildTask(target, mainCompilation, apiBuildDir)
        KotlinPlatformType.js -> createJsApiBuildTask(target, mainCompilation, apiBuildDir)
        KotlinPlatformType.native -> null //createNativeApiBuildTask(target, mainCompilation, apiBuildDir)
    }

    if (targetBuildApiTask != null) {
        val targetCheckApiTask = task<DirectoryCompareTask>("${target.name}CheckApi") {
            group = "verification"
            subject = "'main' compilation of target '${target.name} in $project'"
            description = "Checks API for $subject"
            expectedDir = apiCheckDir
            actualDir = apiBuildDir
            dependsOn(targetBuildApiTask)
            enableWithCompilation(mainCompilation, target)
        }

        checkApiTask.dependsOn(targetBuildApiTask, targetCheckApiTask)

        val targetSyncApiTask = task<Sync>("${target.name}SyncApi") {
            group = "other"
            description = "Syncs API for 'main' compilation of target '${target.name} in $project'"
            from(apiBuildDir)
            into(apiCheckDir)
            dependsOn(targetBuildApiTask)
            enableWithCompilation(mainCompilation, target)
            doFirst {
                apiCheckDir.mkdirs()
            }
        }

        syncApiTask.dependsOn(targetBuildApiTask, targetSyncApiTask)

        val publishApiTask = task<Sync>("publish${target.name.capitalize()}ApiToBuildLocal") {
            group = "publishing"
            description = "Publishes API for 'main' compilation of target '${target.name} in $project'"
            from(apiBuildDir)
            val publishDir = rootProject.buildDir
                .resolve(apiCheck.apiDir)
                .resolve(project.name)
                .resolve(targetName)
            publishDir.mkdirs()
            into(publishDir)
            dependsOn(targetBuildApiTask)
            enableWithCompilation(mainCompilation, target)
        }

        publishLocalTask.dependsOn(publishApiTask)
    }
}

fun Task.enableWithCompilation(
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    target: KotlinTarget
) {
    onlyIf {
        val enabled = mainCompilation.compileKotlinTask.enabled
        if (!enabled)
            logger.warn("$name is disabled because 'main' compilation of target '${target.name}' is not enabled")
        enabled
    }
}
