package kotlinx.team.infra

import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*

class NativeConfiguration {

}

fun Project.configureNativeMultiplatform(native: NativeConfiguration) {
    val multiplatformClass = tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")

    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val multiplatform = multiplatformClass?.let { subproject.extensions.findByType(it) }
            if (multiplatform == null) {
                logger.infra("Skipping native configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }
        }
    }
}

