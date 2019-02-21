package kotlinx.team.infra.node

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import java.io.*
import java.net.*

internal open class NodeSetupTask : DefaultTask() {
    private val config = NodeExtension[project]
    private val variant by lazy { config.buildVariant() }

    init {
        group = NodeExtension.Node
        description = "Download and install a local node/npm version."
    }

    val input: Set<String>
        @Input
        get() {
            return setOf(
                config.download.toString(),
                variant.dependency.toString()
            )
        }

    val destination: File
        @OutputDirectory
        get() = variant.nodeDir
    
    @TaskAction
    fun exec() {
        project.repositories.ivy { repo ->
            repo.name = "Node Distributions at ${config.distBaseUrl}"
            repo.url = URI(config.distBaseUrl)
            repo.patternLayout { layout ->
                layout.artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
                layout.ivy("v[revision]/ivy.xml")
            }
            repo.metadataSources { it.artifact() }
            repo.content { it.includeModule("org.nodejs", "node") }
        }

        val dep = this.project.dependencies.create(variant.dependency)
        val conf = this.project.configurations.detachedConfiguration(dep)
        conf.isTransitive = false
        val result = conf.resolve().single()
        project.logger.infra("Using node distribution from '$result'")

        unpackNodeArchive(result, destination.parentFile) // parent because archive contains name already
        
        if (!variant.windows) {
            File(variant.nodeExec).setExecutable(true)
        }
    }
    
    private fun unpackNodeArchive(archive: File, destination: File) {
        project.logger.infra("Unpacking $archive to $destination")
        when {
            archive.name.endsWith("zip") -> project.copy {
                it.from(project.zipTree(archive))
                it.into(destination)
            }
            else -> {
                project.copy {
                    it.from(project.tarTree(archive))
                    it.into(destination)
                }
            }
        }
    }

    companion object {
        const val NAME: String = "nodeSetup"
    }
}
