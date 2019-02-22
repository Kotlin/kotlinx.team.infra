package kotlinx.team.infra.node

import kotlinx.team.infra.*
import org.gradle.api.*
import org.gradle.api.tasks.*

internal open class NodePlugin : Plugin<Project> {

    private inline fun <reified T> Project.registerGlobal() {
        val clazz = T::class.java
        logger.infra("Registering task '${clazz.simpleName}'")
        project.extensions.extraProperties.set(clazz.simpleName, clazz)
    }

    override fun apply(project: Project): Unit = project.run {
        val ext = NodeExtension.create(this)

        registerGlobal<NodeTask>()
        registerGlobal<NpmTask>()
        registerGlobal<NpmInstallTask>()

        tasks.create(NodeSetupTask.NAME, NodeSetupTask::class.java)

        logger.infra("Register ${ext.node_modules} for clean")
        tasks.maybeCreate("clean", Delete::class.java).apply {
            delete(ext.node_modules)
            delete(ext.nodeModulesContainer.resolve("package-lock.json"))
        }
    }

/*
    private fun Project.addNpmRule(config: NodeExtension) {
        tasks.addRule("Pattern: \"npm_<command>\": Executes an NPM command.") { taskName ->
            val workingDir = config.npmWorkDir
            if (taskName.startsWith("npm_")) {
                val npmTask = project.tasks.create(taskName, NpmTask::class.java)
                npmTask.afterEvaluate(workingDir)

                val tokens = taskName.removePrefix("npm_").split("_").filter { it.isNotEmpty() }
                npmTask.npmCommand = tokens

                if (tokens.first().equals("run", ignoreCase = true)) {
                    npmTask.dependsOn(NpmInstallTask.NAME)
                }
            }
        }
    }
*/

/*
    private fun Project.addYarnRule() {
        // note this rule also makes it possible to specify e.g. "dependsOn yarn_install"
        tasks.addRule("Pattern: \"yarn_<command>\": Executes an Yarn command.") { taskName ->
            if (taskName.startsWith("yarn_")) {
                val yarnTask = tasks.create(taskName, YarnTask::class.java)
                val tokens = taskName.removePrefix("yarn_").split('_').filter { it.isNotEmpty() }
                yarnTask.yarnCommand = tokens

                if (tokens.first().equals("run", ignoreCase = true)) {
                    yarnTask.dependsOn(YarnInstallTask.NAME)
                }
            }
        }
    }
*/

}
