package kotlinx.team.infra.api.jvm

import kotlinx.team.infra.*
import kotlinx.team.infra.api.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import java.io.*

open class JvmApiBuildTask : DefaultTask() {
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

        val signatures = inputClassesDirs.asFileTree.asSequence()
            .filter {
                !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/")
            }
            .map { it.inputStream() }
            .loadApiFromJvmClasses()
            .filterOutNonPublic()

        outputApiDir.resolve("${project.name}.api").bufferedWriter().use { writer ->
            signatures
                .sortedBy { it.name }
                .forEach { api ->
                    writer.append(api.signature).appendln(" {")
                    api.memberSignatures
                        .sortedWith(MEMBER_SORT_ORDER)
                        .forEach { writer.append("\t").appendln(it.signature) }
                    writer.appendln("}\n")
                }
        }
    }
}

fun Project.createJvmApiBuildTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<JvmApiBuildTask> {
    return task<JvmApiBuildTask>("${target.name}BuildApi") {
        group = "build"
        description = "Builds JVM API for 'main' compilation of target '${target.name}'"

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir

        enableWithCompilation(mainCompilation, target)
    }
}
