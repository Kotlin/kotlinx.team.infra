package kotlinx.team.infra.api;

import difflib.*
import kotlinx.team.infra.*
import kotlinx.team.infra.api.js.*
import kotlinx.team.infra.api.jvm.*
import kotlinx.team.infra.api.native.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import java.io.*

public class ApiCheckConfiguration {
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

            subproject.tasks.getByName("check").dependsOn(apiCheckTask)

            multiplatform.targets.all { target ->
                subproject.configureTargetApiCheck(target, apiCheckTask, apiCheck)
            }
        }
    }
}

fun Project.configureTargetApiCheck(
    target: KotlinTarget,
    checkApiTask: Task,
    apiCheck: ApiCheckConfiguration
) {
    val targetName = target.name
    val mainCompilation = target.compilations.findByName("main") ?: run {
        logger.infra("Cannot find 'main' compilation of $target in $this")
        return
    }

    logger.infra("Configuring $target of $this for API check")
    val apiDumpDir = file(buildDir.resolve(apiCheck.apiDir).resolve(targetName))
    val apiCheckDir = file(projectDir.resolve(apiCheck.apiDir).resolve(targetName))

    val targetDumpApiTask = when (target.platformType) {
        KotlinPlatformType.common -> null
        KotlinPlatformType.jvm,
        KotlinPlatformType.androidJvm -> createJvmApiCheckTask(target, mainCompilation, apiDumpDir)
        KotlinPlatformType.js -> createJsApiCheckTask(target, mainCompilation, apiDumpDir)
        KotlinPlatformType.native -> createNativeApiCheckTask(target, mainCompilation, apiDumpDir)
    }

    if (targetDumpApiTask != null) {
        if (!apiCheckDir.exists()) {
            logger.warn("INFRA: API folder '$apiCheckDir' does not exist, API verification will be skipped")
            logger.warn("INFRA: You can copy generated API from '$apiDumpDir'")
        }
        val targetCheckApiTask = task<DirectoryCompareTask>("${target.name}CheckApi") {
            group = "verification"
            subject = "'main' compilation of target '${target.name} in ${this@configureTargetApiCheck}'"
            description = "Checks API for $subject"
            onlyIf { apiCheckDir.exists() }
            expectedDir = apiCheckDir
            actualDir = apiDumpDir
            dependsOn(targetDumpApiTask)
        }

        checkApiTask.dependsOn(targetDumpApiTask, targetCheckApiTask)
    }
}

fun compareFiles(checkFile: File, dumpFile: File): String? {
    val checkText = checkFile.readText()
    val dumpText = dumpFile.readText()
    if (checkText == dumpText)
        return null

    val checkLines = checkText.lines()
    val dumpLines = dumpText.lines()
    val patch = DiffUtils.diff(checkLines, dumpLines)
    val diff = DiffUtils.generateUnifiedDiff(checkFile.toString(), dumpFile.toString(), checkLines, patch, 3)
    return diff.joinToString("\n")
}

