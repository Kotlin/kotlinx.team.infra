package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.plugins.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
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
    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val kotlin = subproject.extensions.findByType(multiplatformExtensionClass)
            if (kotlin == null) {
                logger.infra("Skipping native configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            val extension: Any = if (ideaActive)
                NativeIdeaInfraExtension(subproject, kotlin, "native")
            else
                NativeBuildInfraExtension(subproject, kotlin, "native")

            (kotlin as ExtensionAware).extensions.add("infra", extension)
        }
    }
}

abstract class NativeInfraExtension(
    protected val project: Project,
    protected val kotlin: KotlinMultiplatformExtension,
    protected val sourceSetName: String
) {
    protected val mainSourceSet = kotlin.sourceSets.maybeCreate("${sourceSetName}Main")
    protected val testSourceSet = kotlin.sourceSets.maybeCreate("${sourceSetName}Test")

    protected val sharedConfigs = mutableListOf<KotlinNativeTarget.() -> Unit>()
    fun shared(configure: Closure<*>) = shared { ConfigureUtil.configure(configure, this) }
    fun shared(configure: KotlinNativeTarget.() -> Unit) {
        sharedConfigs.add(configure)
    }

    fun target(name: String) = target(name) { }
    fun target(name: String, configure: Closure<*>) = target(name) { ConfigureUtil.configure(configure, this) }
    abstract fun target(name: String, configure: KotlinNativeTarget.() -> Unit)

    fun common(name: String, configure: Closure<*>) = common(name) { ConfigureUtil.configure(configure, this) }
    abstract fun common(name: String, configure: NativeInfraExtension.() -> Unit)
}

class NativeIdeaInfraExtension(project: Project, kotlin: KotlinMultiplatformExtension, sourceSetName: String) :
    NativeInfraExtension(project, kotlin, sourceSetName) {

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
            kotlin.sourceSets.getByName("${sourceSetName}Main") { sourceSet ->
                sourceSet.kotlin.srcDir("${hostPreset.name}Main/src")
            }
        }
    }

    override fun common(name: String, configure: NativeInfraExtension.() -> Unit) {
        kotlin.sourceSets.create("${name}Main").dependsOn(mainSourceSet)
        kotlin.sourceSets.create("${name}Test").dependsOn(testSourceSet)
        val extension = NativeIdeaInfraExtension(project, kotlin, name)
        extension.configure()
    }
}

class NativeBuildInfraExtension(project: Project, kotlin: KotlinMultiplatformExtension, sourceSetName: String) :
    NativeInfraExtension(project, kotlin, sourceSetName) {

    private val nativePresets = kotlin.presets.filterIsInstance<AbstractKotlinNativeTargetPreset<*>>()

    init {
        project.logger.infra("Configuring native targets for $project for build")
        project.logger.infra("Enabled native targets: ${nativePresets.joinToString { it.name }}")
    }

    override fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        val preset = nativePresets.singleOrNull { it.name == name } ?: return
        project.logger.infra("Creating target '${preset.name}' with dependency on '$sourceSetName'")

        val target = kotlin.targetFromPreset(preset) {
            configure()
            sharedConfigs.forEach { config -> config() }
        }

        kotlin.sourceSets.getByName("${preset.name}Main") { sourceSet ->
            sourceSet.dependsOn(mainSourceSet)
        }
        kotlin.sourceSets.getByName("${preset.name}Test") { sourceSet ->
            sourceSet.dependsOn(testSourceSet)
        }
    }

    override fun common(name: String, configure: NativeInfraExtension.() -> Unit) {
        kotlin.sourceSets.create("${name}Main").dependsOn(mainSourceSet)
        kotlin.sourceSets.create("${name}Test").dependsOn(testSourceSet)
        val extension = NativeBuildInfraExtension(project, kotlin, name)
        extension.configure()
    }
}

private fun createHostManager(): HostManager {
    val managerClass = HostManager::class.java
    val constructors = managerClass.constructors
    val constructor = constructors.first { it.parameterCount == 0 }
    return constructor.newInstance() as HostManager
}
