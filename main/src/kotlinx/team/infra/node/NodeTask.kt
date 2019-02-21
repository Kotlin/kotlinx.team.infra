package kotlinx.team.infra.node

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import org.gradle.process.*
import org.gradle.process.internal.*
import java.io.*
import javax.inject.*

open class NodeTask : DefaultTask() {
    private val config = NodeExtension[project]
    private val variant by lazy { config.buildVariant() }

    private val execAction = getExecActionFactory().newExecAction()

    var execResult: ExecResult? = null
        private set

    var script: File? = null
    var arguments = mutableListOf<String>()
    var options = mutableListOf<String>()

    init {
        group = NodeExtension.Node
        description = "Executes Node script."
        dependsOn(project.rootProject.tasks.named(NodeSetupTask.NAME))
    }

    @Inject
    protected open fun getExecActionFactory(): ExecActionFactory = throw UnsupportedOperationException()

    fun advanced(configure: (ExecAction) -> Unit) {
        execAction.apply(configure)
    }

    @TaskAction
    fun exec() {
        val script = script ?: throw KotlinInfrastructureException("Cannot run Node task without specified 'script'")
        execAction.apply {
            workingDir = config.nodeModulesContainer
            args(options)
            args(script.absolutePath)
            args(arguments)
            executable = variant.nodeExec
        }
        execResult = execAction.execute()
    }

    companion object {
        const val NAME: String = "node"
    }
}

