package kotlinx.team.infra.api.native

import kotlinx.team.infra.*
import kotlinx.team.infra.api.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.library.*
import java.io.*

open class NativeApiBuildTask : DefaultTask() {
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
        cleanup(outputApiDir)
        outputApiDir.mkdirs()

        val generator = ModuleDescriptorApiGenerator(project, outputApiDir)
        inputClassesDirs.files
            .filter { it.exists() && it.name.endsWith(KLIB_FILE_EXTENSION_WITH_DOT) }
            .forEach { lib ->
                val module = generator.project.createModuleDescriptor(nativeTarget, lib, inputDependencies.files)
                generator.generate(module)
            }
    }

}

fun Project.createNativeApiBuildTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<NativeApiBuildTask> {
    return task<NativeApiBuildTask>("${target.name}BuildApi") {
        group = "build"
        description = "Builds Native API for 'main' compilation of target '${target.name}'"
        nativeTarget = (target as? KotlinNativeTarget)?.konanTarget?.name ?: ""

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir
        enableWithCompilation(mainCompilation, target)
    }
}
