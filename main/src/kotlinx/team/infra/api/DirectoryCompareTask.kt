package kotlinx.team.infra.api

import difflib.*
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

    @OutputFile
    @Optional
    val dummyOutputFile: File? = null

    var subject: String = "Directory comparison"

    @TaskAction
    fun verify() {
        if (!expectedDir.exists())
            throw KotlinInfrastructureException("Expected API folder '$expectedDir' does not exist")
        if (!actualDir.exists())
            throw KotlinInfrastructureException("Actual API folder '$actualDir' does not exist")

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

    private fun compareFiles(checkFile: File, builtFile: File): String? {
        val checkText = checkFile.readText()
        val builtText = builtFile.readText()
        
        // We don't compare full text because newlines on Windows & Linux/macOS are different
        val checkLines = checkText.lines()
        val builtLines = builtText.lines()
        if (checkLines == builtLines)
            return null
        
        val patch = DiffUtils.diff(checkLines, builtLines)
        val diff = DiffUtils.generateUnifiedDiff(checkFile.toString(), builtFile.toString(), checkLines, patch, 3)
        return diff.joinToString("\n")
    }
}