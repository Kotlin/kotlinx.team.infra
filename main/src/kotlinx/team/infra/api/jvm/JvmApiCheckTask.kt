package kotlinx.team.infra.api.jvm

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import java.io.*

open class JvmApiCheckTask : DefaultTask() {
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
                .onEach { 
                    println("SIGNATURE: ${it.signature}")
                }
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

fun Project.createJvmApiCheckTask(
    target: KotlinTarget,
    mainCompilation: KotlinCompilation<KotlinCommonOptions>,
    apiBuildDir: File
): TaskProvider<JvmApiCheckTask> {
    return task<JvmApiCheckTask>("${target.name}CheckApi") {
        group = "verification"
        description = "Runs JVM API checks for 'main' compilation of target '${target.name}'"

        inputClassesDirs = mainCompilation.output.allOutputs
        inputDependencies = mainCompilation.compileDependencyFiles
        outputApiDir = apiBuildDir

        doFirst {
            apiBuildDir.mkdirs()
        }
    }
}
