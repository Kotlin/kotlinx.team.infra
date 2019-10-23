package kotlinx.team.infra.api.js

import kotlinx.team.infra.*
import kotlinx.team.infra.api.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.incremental.components.*
import org.jetbrains.kotlin.js.resolve.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.serialization.js.*
import org.jetbrains.kotlin.storage.*
import org.jetbrains.kotlin.utils.*
import java.io.*

open class JsApiBuildTask : DefaultTask() {
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
        cleanup(outputApiDir)
        outputApiDir.mkdirs()

        val generator = ModuleDescriptorApiGenerator(outputApiDir)
        inputClassesDirs.files.forEach { lib ->
            generator.generateJavaScript(lib)
        }
    }

    private fun ModuleDescriptorApiGenerator.generateJavaScript(lib: File) {
        val configuration = CompilerConfiguration()
        val languageVersionSettings = configuration.languageVersionSettings
        val storageManager = LockBasedStorageManager("Inspect")

        val dependencies = inputDependencies.flatMap {
            loadDescriptors(it, languageVersionSettings, storageManager)
        }
        val descriptors = loadDescriptors(lib, languageVersionSettings, storageManager, dependencies)
        descriptors.forEach { generate(it) }
    }

    private fun loadDescriptors(
        lib: File,
        languageVersionSettings: LanguageVersionSettings,
        storageManager: LockBasedStorageManager,
        dependencies: List<ModuleDescriptorImpl> = listOf()
    ): List<ModuleDescriptorImpl> {
        val modules = KotlinJavascriptMetadataUtils.loadMetadata(lib)
        return modules.map { metadata ->
            val skipCheck = languageVersionSettings.getFlag(AnalysisFlags.skipMetadataVersionCheck)
            assert(metadata.version.isCompatible() || skipCheck) {
                "Expected JS metadata version " + JsMetadataVersion.INSTANCE + ", but actual metadata version is " + metadata.version
            }

            val module = ModuleDescriptorImpl(
                Name.special("<" + metadata.moduleName + ">"),
                storageManager,
                JsPlatformAnalyzerServices.builtIns
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
            module.setDependencies(listOf(module, JsPlatformAnalyzerServices.builtIns.builtInsModule) + dependencies)
            module.initialize(provider)
            module
        }
    }
}

fun Project.createJsApiBuildTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<JsApiBuildTask> {
    return task<JsApiBuildTask>("${target.name}BuildApi") {
        group = "build"
        description = "Builds JS API for 'main' compilation of target '${target.name}'"

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir
        enableWithCompilation(mainCompilation, target)
    }
}


