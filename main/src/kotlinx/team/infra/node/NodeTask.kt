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

    var script: String? = null
    var arguments = mutableListOf<String>()
    var options = mutableListOf<String>()

    fun arguments(vararg values: String) {
        arguments.addAll(values)
    }

    fun options(vararg values: String) {
        options.addAll(values)
    }
    
    init {
        group = NodeExtension.Node
        description = "Executes Node script."
        dependsOn(project.rootProject.tasks.named(NodeSetupTask.NAME))
    }

    @Inject
    protected open fun getExecActionFactory(): ExecActionFactory = throw UnsupportedOperationException()

    private var advancedConfigure: ((ExecAction) -> Unit)? = null

    fun advanced(configure: (ExecAction) -> Unit) {
        advancedConfigure = configure
    }

    @TaskAction
    fun exec() {
        val script = script ?: throw KotlinInfrastructureException("Cannot run Node task without specified 'script'")
        execAction.apply {
            workingDir = project.buildDir
            val nodeModulesList = mutableListOf<String>()
            var prj: Project? = project
            while (prj != null) {
                val folder = prj.buildDir.resolve("node_modules")
                if (folder.exists()) {
                    nodeModulesList.add(folder.absolutePath)
                }
                prj = prj.parent
            }

            val nodePath = nodeModulesList.joinToString(if (variant.windows) ";" else ":")
            logger.infra("Setting NODE_PATH = $nodePath")
            environment("NODE_PATH", nodePath)
            args(options)
            val scriptFile = File(script)
            args(scriptFile.toString())
            args(arguments)
            executable = variant.nodeExec
        }
        advancedConfigure?.let { it(execAction) }
        logger.infra("Executing: ${execAction.commandLine}")
        execResult = execAction.execute()
    }

    companion object {
        const val NAME: String = "node"
    }
}

