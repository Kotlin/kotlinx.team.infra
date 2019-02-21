package kotlinx.team.infra

import kotlinx.team.infra.node.*
import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*

class NodeConfiguration {
    var version = "10.15.1"
    var npmVersion = "5.7.1"
    var mochaVersion = "4.1.0"
}

fun Project.configureNode(node: NodeConfiguration) {
    val multiplatformClass =
        tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")
    var nodeConfigured = false

    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") {
            val multiplatform = multiplatformClass?.let { subproject.extensions.findByType(it) }
            if (multiplatform == null) {
                logger.infra("Skipping node configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            if (!nodeConfigured) {
                nodeConfigured = true
                applyNodePlugin(node)
            }
        }
    }
}

fun Project.applyNodePlugin(node: NodeConfiguration) {
    logger.infra("Enabling node plugin in $this")
    pluginManager.apply(NodePlugin::class.java)
    val extension = NodeExtension[this].apply {
        version = node.version
        npmVersion = node.npmVersion
    }
}
