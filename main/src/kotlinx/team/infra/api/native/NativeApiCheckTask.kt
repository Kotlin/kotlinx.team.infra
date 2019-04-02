package kotlinx.team.infra.api.native

import kotlinx.team.infra.*
import kotlinx.team.infra.api.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.library.*
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.*
import org.jetbrains.kotlin.storage.*
import java.io.*

open class NativeApiCheckTask : DefaultTask() {
    @Input
    lateinit var nativeTarget: String

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    lateinit var inputClassesDirs: FileCollection

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    lateinit var inputDependencies: FileCollection

    @OutputDirectory
    lateinit var outputApiDir: File

    @TaskAction
    fun generate() {
        val generator = ModuleDescriptorApiGenerator(project, outputApiDir)
        inputClassesDirs.files.forEach { lib ->
            generator.generateNative(lib)
        }
    }

    private fun ModuleDescriptorApiGenerator.generateNative(lib: File) {
        if (!lib.exists())
            return // empty sources yield missing output file. no file - no api

        if (!lib.name.endsWith(KLIB_FILE_EXTENSION_WITH_DOT)) {
            return // ignore non-klib files
        }

        val nativeTarget = nativeTarget
        if (nativeTarget.isEmpty())
            throw KotlinInfrastructureException("nativeTarget should be specified for API generator for native targets")

        val konanTarget = PredefinedKonanTargets.getByName(nativeTarget)!!
        val versionSpec = LanguageVersionSettingsImpl(
            LanguageVersion.LATEST_STABLE,
            ApiVersion.LATEST_STABLE
        )
        val ABI_VERSION = 1

        val pathResolver = ProvidedPathResolver(logger, inputDependencies.files, konanTarget)
        val libraryResolver = pathResolver.libraryResolver(ABI_VERSION)

        val factory = KonanFactories.DefaultDeserializedDescriptorFactory

        val konanFile = org.jetbrains.kotlin.konan.file.File(lib.canonicalPath)

        val library = createKonanLibrary(konanFile, ABI_VERSION, konanTarget, false)
        val unresolvedDependencies = library.unresolvedDependencies
        val storageManager = LockBasedStorageManager()

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
        generate(module)

    }
}

fun Project.createNativeApiCheckTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<NativeApiCheckTask> {
    return task<NativeApiCheckTask>("${target.name}CheckApi") {
        group = "verification"
        description = "Runs Native API checks for 'main' compilation of target '${target.name}'"
        nativeTarget = (target as? KotlinNativeTarget)?.konanTarget?.name ?: ""

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir

        doFirst {
            apiBuildDir.mkdirs()
        }
    }
}
