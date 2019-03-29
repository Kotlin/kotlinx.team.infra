package kotlinx.team.infra.api

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.incremental.components.*
import org.jetbrains.kotlin.js.resolve.*
import org.jetbrains.kotlin.konan.library.*
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.serialization.js.*
import org.jetbrains.kotlin.storage.*
import org.jetbrains.kotlin.utils.*
import java.io.*

open class ApiCheckTask : DefaultTask() {
    @Input
    lateinit var platformType: KotlinPlatformType

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
        val generator = ApiGenerator(outputApiDir)
        inputClassesDirs.files.forEach { lib ->
            when (platformType) {
                KotlinPlatformType.native -> generator.generateNative(lib)
                KotlinPlatformType.js -> generator.generateJavaScript(lib)
                KotlinPlatformType.jvm, KotlinPlatformType.androidJvm -> generator.generateJvm(lib)
                KotlinPlatformType.common -> generator.generateCommon(lib)
            }
        }
    }

    private fun ApiGenerator.generateCommon(lib: File) {
        println("COMMON: $lib")
    }

    private fun ApiGenerator.generateJvm(lib: File) {
        println("JVM: $lib")
    }

    private fun ApiGenerator.generateJavaScript(lib: File) {
        val configuration = CompilerConfiguration()
        val languageVersionSettings = configuration.languageVersionSettings

        val modules = KotlinJavascriptMetadataUtils.loadMetadata(lib)
        modules.forEach { metadata ->
            val storageManager = LockBasedStorageManager()
            val skipCheck = languageVersionSettings.getFlag(AnalysisFlags.skipMetadataVersionCheck)
            assert(metadata.version.isCompatible() || skipCheck) {
                "Expected JS metadata version " + JsMetadataVersion.INSTANCE + ", but actual metadata version is " + metadata.version
            }

            val module = ModuleDescriptorImpl(
                Name.special("<" + metadata.moduleName + ">"),
                storageManager,
                JsPlatform.builtIns
            )
            val (header, body) = KotlinJavascriptSerializationUtil.readModuleAsProto(
                metadata.body,
                metadata.version
            )
            val provider = createKotlinJavascriptPackageFragmentProvider(
                storageManager,
                module,
                header,
                body,
                metadata.version,
                CompilerDeserializationConfiguration(languageVersionSettings),
                LookupTracker.DO_NOTHING
            )
            module.setDependencies(listOf(module, JsPlatform.builtIns.builtInsModule))
            module.initialize(provider)
            generate(module)
        }
    }

    private fun ApiGenerator.generateNative(lib: File) {
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