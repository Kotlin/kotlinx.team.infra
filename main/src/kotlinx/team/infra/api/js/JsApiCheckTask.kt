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
import org.jetbrains.kotlin.konan.library.*
import org.jetbrains.kotlin.konan.target.*
import org.jetbrains.kotlin.konan.util.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.serialization.js.*
import org.jetbrains.kotlin.storage.*
import org.jetbrains.kotlin.utils.*
import java.io.*

open class JsApiCheckTask : DefaultTask() {
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
            generator.generateJavaScript(lib)
        }
    }

    private fun ModuleDescriptorApiGenerator.generateJavaScript(lib: File) {
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
}

fun Project.createJsApiCheckTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<JsApiCheckTask> {
    return task<JsApiCheckTask>("${target.name}CheckApi") {
        group = "verification"
        description = "Runs JS API checks for 'main' compilation of target '${target.name}'"

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir

        doFirst {
            apiBuildDir.mkdirs()
        }
    }
}


