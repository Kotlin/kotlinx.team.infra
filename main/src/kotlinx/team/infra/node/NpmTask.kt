package kotlinx.team.infra.node

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import org.gradle.process.*
import org.gradle.process.internal.*
import javax.inject.*

open class NpmTask : DefaultTask() {
    private val config = NodeExtension[project]
    private val variant by lazy { config.buildVariant() }

    private val execAction = getExecActionFactory().newExecAction()

    var execResult: ExecResult? = null
        private set

    var command: String? = null
    var arguments = mutableListOf<String>()
    var options = mutableListOf<String>()

    init {
        group = NodeExtension.Node
        description = "Executes npm command."
        dependsOn(project.rootProject.tasks.named(NodeSetupTask.NAME))
    }

    fun advanced(configure: (ExecAction) -> Unit) {
        execAction.apply(configure)
    }

    @TaskAction
    fun exec() {
        val command = command ?: throw KotlinInfrastructureException("Cannot run npm task without specified 'command'")
        execAction.apply {
            workingDir = config.nodeModulesContainer
            workingDir.mkdirs()
            args(options)
            args(command)
            args(arguments)
            executable = variant.npmExec
        }
        execResult = execAction.execute()
    }


    @Inject
    protected open fun getExecActionFactory(): ExecActionFactory = throw UnsupportedOperationException()
}

