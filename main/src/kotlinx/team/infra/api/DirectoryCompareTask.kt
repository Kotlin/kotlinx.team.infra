package kotlinx.team.infra.api

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import java.io.*

open class DirectoryCompareTask : DefaultTask() {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    lateinit var expectedDir: File

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    lateinit var actualDir: File

    var subject: String = "Directory comparison"

    @TaskAction
    fun verify() {
        val actualFiles = mutableSetOf<RelativePath>()
        val expectedFiles = mutableSetOf<RelativePath>()
        logger.infra("Comparing $expectedDir and $actualDir")
        project.fileTree(actualDir).visit { file ->
            actualFiles.add(file.relativePath)
        }
        project.fileTree(expectedDir).visit { file ->
            expectedFiles.add(file.relativePath)
        }

        val missingFiles = expectedFiles - actualFiles
        val extraFiles = actualFiles - expectedFiles

        if (missingFiles.isNotEmpty()) {
            throw KotlinInfrastructureException("API check failed for $subject.\nMissing files: $missingFiles")
        }
        if (extraFiles.isNotEmpty()) {
            throw KotlinInfrastructureException("API check failed for $subject.\nExtra files: $extraFiles")
        }

        val diffSet = mutableSetOf<String>()
        expectedFiles.forEach { relative ->
            val expectedFile = relative.getFile(expectedDir)
            val actualFile = relative.getFile(actualDir)
            val diff = compareFiles(expectedFile, actualFile)
            if (diff != null)
                diffSet.add(diff)
        }
        if (diffSet.isNotEmpty()) {
            val diffText = diffSet.joinToString("\n\n")
            throw KotlinInfrastructureException("API check failed for $subject. Files are different.\n$diffText")
        }
    }
}