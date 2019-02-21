package kotlinx.team.infra.node

import kotlinx.team.infra.*
import org.gradle.api.*
import java.io.*

internal open class NodeExtension(project: Project) {
    private val cacheDir = project.gradle.gradleUserHomeDir.also {
        project.logger.infra("Storing cached files in $it")
    }

    var installationDir = cacheDir.resolve("nodejs")
    var npmWorkDir = cacheDir.resolve("npm")
    var yarnWorkDir = cacheDir.resolve("yarn")

    var nodeModulesContainer = project.buildDir

    var version = "6.9.1"
    var distBaseUrl = "https://nodejs.org/dist"

    var npmVersion = ""
    var npmCommand = "npm"

    var yarnVersion = ""
    var yarnCommand = "yarn"

    var download = false

    fun buildVariant(): Variant {
        val platform = Platform.name
        val architecture = Platform.architecture

        val nodeDir = installationDir.resolve("node-v${version}-$platform-$architecture")
        val isWindows = Platform.name == "win"

        fun executable(command: String, value: String, windowsExtension: String): String =
            if (isWindows && command == value) "$value.$windowsExtension" else value

        fun String.downloaded(bin: File) = if (download) File(bin, this).absolutePath else this

        val nodeBinDir = if (isWindows) nodeDir else nodeDir.resolve("bin")

        fun dependency(osName: String, osArch: String, type: String): String {
            return "org.nodejs:node:${version}:$osName-$osArch@$type"
        }

        return Variant(
            nodeDir = nodeDir,
            nodeBinDir = nodeBinDir,
            nodeExec = executable("node", npmCommand, "exe").downloaded(nodeBinDir),
            npmExec = executable("npm", npmCommand, "cmd").downloaded(nodeBinDir),
            windows = isWindows,
            dependency = dependency(platform, architecture, if (isWindows) "zip" else "tar.gz")
        )
    }

    companion object {
        const val Node: String = "node"

        operator fun get(project: Project): NodeExtension {
            val extension = project.extensions.findByType(NodeExtension::class.java)
            if (extension != null)
                return extension
            
            val parentProject = project.parent
            if (parentProject != null)
                return get(parentProject)
            
            throw KotlinInfrastructureException("NodeExtension is not installed")
        }
        
        fun create(project: Project): NodeExtension {
            project.logger.infra("Installing NodeExtension into $project")
            return project.extensions.create(Node, NodeExtension::class.java, project)
        }
    }
}
