package kotlinx.team.infra

import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.logging.*
import org.gradle.api.plugins.*
import org.gradle.util.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.library.*
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.*
import org.jetbrains.kotlin.storage.*
import java.io.*
import java.nio.file.*

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
                NativeIdeaInfraExtension(subproject, kotlin)
            else
                NativeBuildInfraExtension(subproject, kotlin)

            (kotlin as ExtensionAware).extensions.add("infra", extension)
        }
    }
}

abstract class NativeInfraExtension(
    protected val project: Project,
    protected val kotlin: KotlinMultiplatformExtension
) {
    protected val sharedConfigs = mutableListOf<KotlinNativeTarget.() -> Unit>()
    fun shared(configure: Closure<*>) = shared { ConfigureUtil.configure(configure, this) }
    fun shared(configure: KotlinNativeTarget.() -> Unit) {
        sharedConfigs.add(configure)
    }

    fun target(name: String) = target(name) { }
    fun target(name: String, configure: Closure<*>) = target(name) { ConfigureUtil.configure(configure, this) }
    abstract fun target(name: String, configure: KotlinNativeTarget.() -> Unit)
}

class NativeIdeaInfraExtension(project: Project, kotlin: KotlinMultiplatformExtension) :
    NativeInfraExtension(project, kotlin) {

    private val hostManager = createHostManager()

    private val hostTarget = HostManager.host

    private val hostPreset = kotlin.presets.filterIsInstance<KotlinNativeTargetPreset>().single { preset ->
        hostManager.isEnabled(preset.konanTarget) && hostTarget == preset.konanTarget
    }

    init {
        project.logger.infra("Configuring native targets for $project for IDEA")
        project.logger.infra("Host preset: ${hostPreset.name}")
    }

    override fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        if (name != hostPreset.name)
            return

        kotlin.targetFromPreset(hostPreset, "native") {
            configure()
            sharedConfigs.forEach { it() }
        }

        project.afterEvaluate {
            kotlin.sourceSets.getByName("nativeMain") { sourceSet ->
                sourceSet.kotlin.srcDir("${hostPreset.name}Main/src")
            }
        }
    }
}

class NativeBuildInfraExtension(project: Project, kotlin: KotlinMultiplatformExtension) :
    NativeInfraExtension(project, kotlin) {

    private val nativePresets = kotlin.presets.filterIsInstance<KotlinNativeTargetPreset>()

    private val nativeMain = kotlin.sourceSets.create("nativeMain")
    private val nativeTest = kotlin.sourceSets.create("nativeTest")

    init {
        project.logger.infra("Configuring native targets for $project for build")
        project.logger.infra("Enabled native targets: ${nativePresets.joinToString { it.name }}")
    }

    override fun target(name: String, configure: KotlinNativeTarget.() -> Unit) {
        val preset = nativePresets.singleOrNull { it.name == name } ?: return
        project.logger.infra("Creating target '${preset.name}' with dependency on 'native'")

        val target = kotlin.targetFromPreset(preset) {
            configure()
            sharedConfigs.forEach { config -> config() }
        }

        kotlin.sourceSets.getByName("${preset.name}Main") { sourceSet ->
            sourceSet.dependsOn(nativeMain)
        }
        kotlin.sourceSets.getByName("${preset.name}Test") { sourceSet ->
            sourceSet.dependsOn(nativeTest)
        }
    }
}

 fun Project.createModuleDescriptor(nativeTarget: String, lib: File, dependencyPaths: Set<File>): ModuleDescriptor {
    if (nativeTarget.isEmpty())
        throw KotlinInfrastructureException("nativeTarget should be specified for API generator for native targets")

    val konanTarget = PredefinedKonanTargets.getByName(nativeTarget)!!
    val versionSpec = LanguageVersionSettingsImpl(
        LanguageVersion.LATEST_STABLE,
        ApiVersion.LATEST_STABLE
    )
    val ABI_VERSION = 8

    val pathResolver = ProvidedPathResolver(logger, dependencyPaths, konanTarget)
    val libraryResolver = pathResolver.libraryResolver(ABI_VERSION)

    val factory = KonanFactories.DefaultDeserializedDescriptorFactory

    val konanFile = org.jetbrains.kotlin.konan.file.File(lib.canonicalPath)

    val library = createKonanLibrary(konanFile, ABI_VERSION, konanTarget, false)
    val unresolvedDependencies = library.unresolvedDependencies
    val storageManager = LockBasedStorageManager("Inspect")

    val module = factory.createDescriptorAndNewBuiltIns(library, versionSpec, storageManager)

    val dependencies = libraryResolver.resolveWithDependencies(unresolvedDependencies)
    val dependenciesResolved = KonanFactories.DefaultResolvedDescriptorsFactory.createResolved(
        dependencies,
        storageManager,
        null,
        versionSpec
    )

    val dependenciesDescriptors = dependenciesResolved.resolvedDescriptors
    val forwardDeclarationsModule = dependenciesResolved.forwardDeclarationsModule

    module.setDependencies(listOf(module) + dependenciesDescriptors + forwardDeclarationsModule)
    return module
}

class ProvidedPathResolver(
    private val logger: Logger,
    private val dependencies: Set<File>,
    override val target: KonanTarget
) : SearchPathResolverWithTarget {

    override val searchRoots: List<org.jetbrains.kotlin.konan.file.File> get() = emptyList()

    private val nameMap = dependencies
        // TODO: what's wrong with JARs? They seem common libs, how does native ignores them?
        .filter { it.extension != "jar" }
        .map {
            val file = org.jetbrains.kotlin.konan.file.File(it.absolutePath)
            // Need to load library to know its uniqueName, some libs like atomicfu has it different from klib file name
            createKonanLibrary(file, 1, target)
        }
        .associateBy { it.uniqueName }
        .mapValues { it.value.libraryFile }

    override fun resolve(givenPath: String): org.jetbrains.kotlin.konan.file.File {
        val path = Paths.get(givenPath)
        return when {
            path.isAbsolute -> org.jetbrains.kotlin.konan.file.File(path)
            else -> {
                val file = nameMap[givenPath]
                if (file != null)
                    return file

                logger.error("Cannot resolve library $givenPath with the following dependencies:")
                logger.error(dependencies.joinToString(prefix = "  ", separator = "\n  "))
                throw Exception("Cannot resolve library '$givenPath' with $nameMap")
            }
        }
    }

    override fun defaultLinks(noStdLib: Boolean, noDefaultLibs: Boolean): List<org.jetbrains.kotlin.konan.file.File> =
        emptyList()
}

private fun createHostManager(): HostManager {
    val managerClass = HostManager::class.java
    val constructors = managerClass.constructors
    val constructor = constructors.first { it.parameterCount == 0 }
    return constructor.newInstance() as HostManager
}
