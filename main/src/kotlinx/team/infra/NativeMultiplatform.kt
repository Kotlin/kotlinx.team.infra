package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.target.*

fun Project.configureNativeMultiplatform() {
    val multiplatformExtensionClass =
        tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")
    if (multiplatformExtensionClass == null) {
        logger.infra("Skipping native configuration because multiplatform plugin has not been applied")
        return
    }

    val ideaActive = System.getProperty("idea.active")?.toBoolean() ?: false
    subprojects {
        val subproject = this
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") {
            val kotlin = subproject.extensions.findByType(multiplatformExtensionClass)
            if (kotlin == null) {
                logger.infra("Skipping native configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            val useNativeBuildInfraInIdea = subproject.findProperty("useNativeBuildInfraInIdea")?.toString()?.toBoolean() ?: false
            val commonMain = kotlin.sourceSets.getByName("commonMain")
            val commonTest = kotlin.sourceSets.getByName("commonTest")
            val extension: Any = if (ideaActive && !useNativeBuildInfraInIdea)
                NativeIdeaInfraExtension(subproject, kotlin, "native", commonMain, commonTest)
            else
                NativeBuildInfraExtension(subproject, kotlin, "native", commonMain, commonTest)

            (kotlin as ExtensionAware).extensions.add("infra", extension)
        }
    }
}

abstract class NativeInfraExtension(
    protected val project: Project,
    protected val kotlin: KotlinMultiplatformExtension,
    protected val sourceSetName: String,
    commonMainSourceSet: KotlinSourceSet,
    commonTestSourceSet: KotlinSourceSet,
) {
    protected val mainSourceSet: KotlinSourceSet = kotlin.sourceSets
        .maybeCreate("${sourceSetName}Main")
        .apply { dependsOn(commonMainSourceSet) }
    protected val testSourceSet: KotlinSourceSet = kotlin.sourceSets
        .maybeCreate("${sourceSetName}Test")
        .apply { dependsOn(commonTestSourceSet) }

    protected val sharedConfigs = mutableListOf<KotlinNativeTarget.() -> Unit>()
    fun shared(configure: Closure<*>) = shared { project.configure(this, configure) }
    fun shared(configure: KotlinNativeTarget.() -> Unit) {
        sharedConfigs.add(configure)
    }

    fun target(name: String) = target(name) { }
    fun target(name: String, configure: Closure<*>) = target(name) { project.configure(this, configure) }
    abstract fun target(name: String, configure: KotlinNativeTarget.() -> Unit)

    fun common(name: String, configure: Closure<*>) = common(name) { project.configure(this, configure) }
    abstract fun common(name: String, configure: NativeInfraExtension.() -> Unit)
}

class NativeIdeaInfraExtension(
    project: Project,
    kotlin: KotlinMultiplatformExtension,
    sourceSetName: String,
    commonMainSourceSet: KotlinSourceSet,
    commonTestSourceSet: KotlinSourceSet,
) : NativeInfraExtension(project, kotlin, sourceSetName, commonMainSourceSet, commonTestSourceSet) {

    private val hostManager = createHostManager()

    private val hostTarget = HostManager.host

    private val hostPreset =
        kotlin.presets.filterIsInstance<AbstractKotlinNativeTargetPreset<*>>().let { nativePresets ->
            nativePresets.singleOrNull { preset ->
                hostManager.isEnabled(preset.konanTarget) && hostTarget == preset.konanTarget
            } ?: error("No native preset of ${nativePresets.map { it.konanTarget }} matches current host target $hostTarget")
        }

    init {
        project.logger.infra("Configuring native targets for $project for IDEA")
        project.logger.infra("Host preset: ${hostPreset.name}")
    }

    override fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        if (name != hostPreset.name)
            return

        kotlin.targetFromPreset(hostPreset, sourceSetName) {
            configure()
            sharedConfigs.forEach { it() }
        }

        project.afterEvaluate {
            kotlin.sourceSets.getByName("${sourceSetName}Main") {
                kotlin.srcDir("${hostPreset.name}Main/src")
            }
        }
    }

    override fun common(name: String, configure: NativeInfraExtension.() -> Unit) {
        val extension = NativeIdeaInfraExtension(project, kotlin, name, mainSourceSet, testSourceSet)
        extension.configure()
    }
}

class NativeBuildInfraExtension(
    project: Project,
    kotlin: KotlinMultiplatformExtension,
    sourceSetName: String,
    commonMainSourceSet: KotlinSourceSet,
    commonTestSourceSet: KotlinSourceSet,
) : NativeInfraExtension(project, kotlin, sourceSetName, commonMainSourceSet, commonTestSourceSet) {

    private val nativePresets = kotlin.presets.filterIsInstance<AbstractKotlinNativeTargetPreset<*>>()

    init {
        project.logger.infra("Configuring native targets for $project for build")
        project.logger.infra("Enabled native targets: ${nativePresets.joinToString { it.name }}")
    }

    override fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        val preset = nativePresets.singleOrNull { it.name == name } ?: return
        project.logger.infra("Creating target '${preset.name}' with dependency on '$sourceSetName'")

        kotlin.targetFromPreset(preset) {
            configure()
            sharedConfigs.forEach { config -> config() }
        }

        kotlin.sourceSets.getByName("${preset.name}Main") {
            dependsOn(mainSourceSet)
        }
        kotlin.sourceSets.getByName("${preset.name}Test") {
            dependsOn(testSourceSet)
        }
    }

    override fun common(name: String, configure: NativeInfraExtension.() -> Unit) {
        val extension = NativeBuildInfraExtension(project, kotlin, name, mainSourceSet, testSourceSet)
        extension.configure()
    }
}

private fun createHostManager(): HostManager {
    val managerClass = HostManager::class.java
    val constructors = managerClass.constructors
    val constructor = constructors.first { it.parameterCount == 0 }
    return constructor.newInstance() as HostManager
}
