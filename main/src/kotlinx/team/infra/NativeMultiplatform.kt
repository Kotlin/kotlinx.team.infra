package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.target.*

private val hostManager = HostManager()
private val hostTarget = HostManager.host

fun Project.configureNativeMultiplatform() {
    val multiplatformExtensionClass = tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")
    if (multiplatformExtensionClass == null) {
        logger.infra("Skipping native configuration because multiplatform plugin has not been applied")
        return
    }
    
    val ideaActive = System.getProperty("idea.active")?.toBoolean() ?: false
    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val kotlin = multiplatformExtensionClass?.let { subproject.extensions.findByType(it) }
            if (kotlin == null) {
                logger.infra("Skipping native configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            val extension: Any = if (ideaActive)
                NativeIdeaInfraExtension(subproject, kotlin)
            else
                NativeBuildInfraExtension(subproject, kotlin)

            (kotlin as ExtensionAware).extensions.add("infra", extension)
        }
    }
}

class NativeIdeaInfraExtension(
    private val project: Project,
    private val kotlin: KotlinMultiplatformExtension
) {
    private val hostPreset = kotlin.presets.filterIsInstance<KotlinNativeTargetPreset>().single { preset ->
        hostManager.isEnabled(preset.konanTarget) && hostTarget == preset.konanTarget
    }

    init {
        project.logger.infra("Configuring native targets for $project for IDEA")
        project.logger.infra("Host preset: ${hostPreset.name}")
    }

    fun target(name: String) = target(name) { }
    fun target(name: String, configure: Closure<*>) = target(name) { ConfigureUtil.configure(configure, this) }
    fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        if (name != hostPreset.name)
            return

        kotlin.targetFromPreset(hostPreset, "native") {
            configure()
        }

        project.afterEvaluate {
            kotlin.sourceSets.getByName("nativeMain") { sourceSet ->
                sourceSet.kotlin.srcDir("${hostPreset.name}Main/src")
            }
        }
    }
}

class NativeBuildInfraExtension(
    private val project: Project,
    private val kotlin: KotlinMultiplatformExtension
) {
    private val enabledNativePresets = kotlin.presets.filterIsInstance<KotlinNativeTargetPreset>().filter { preset ->
        hostManager.isEnabled(preset.konanTarget)
    }

    private val nativeMain = kotlin.sourceSets.create("nativeMain")
    private val nativeTest = kotlin.sourceSets.create("nativeTest")

    init {
        project.logger.infra("Configuring native targets for $project for build")
        project.logger.infra("Enabled native targets: ${enabledNativePresets.joinToString { it.name }}")
    }

    fun target(name: String) = target(name) { }
    fun target(name: String, configure: Closure<*>) = target(name) { ConfigureUtil.configure(configure, this) }
    fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        val preset = enabledNativePresets.singleOrNull { it.name == name } ?: return
        project.logger.infra("Creating target '${preset.name}' with dependency on 'native'")

        val target = kotlin.targetFromPreset(preset) {}
        target.configure()
        kotlin.sourceSets.getByName("${preset.name}Main") { sourceSet ->
            sourceSet.dependsOn(nativeMain)
        }
        kotlin.sourceSets.getByName("${preset.name}Test") { sourceSet ->
            sourceSet.dependsOn(nativeTest)
        }
    }
}

