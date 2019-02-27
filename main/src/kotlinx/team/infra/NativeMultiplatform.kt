package kotlinx.team.infra

import org.gradle.api.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.konan.target.*

class NativeConfiguration {
    internal val hostManager = HostManager()

    val hostTarget = HostManager.host

    val isLinux get() = HostManager.hostIsLinux
    val isMingw get() = HostManager.hostIsMingw
    val isMacOS get() = HostManager.hostIsMac

    var includedTargets: MutableList<String> = mutableListOf()
    fun include(vararg target: String) {
        includedTargets.addAll(target)
    }
}

fun Project.configureNativeMultiplatform(native: NativeConfiguration) {
    val multiplatformExtensionClass =
        tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")
    val ideaActive = System.getProperty("idea.active")?.toBoolean() ?: false
    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val kotlin = multiplatformExtensionClass?.let { subproject.extensions.findByType(it) }
            if (kotlin == null) {
                logger.infra("Skipping native configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }
            val enabledNativePresets = kotlin.presets.filterIsInstance<KotlinNativeTargetPreset>().filter { preset ->
                native.hostManager.isEnabled(preset.konanTarget)
            }
            logger.infra("Configuring native targets for $subproject for ${if (ideaActive) "IDEA" else "build"}")
            // kotlin.presets.add(KotlinNativeTargetPreset("hostNative", subproject, native.hostTarget, "1.3.20"))
            logger.infra("Enabled native targets: ${enabledNativePresets.joinToString { it.name }}")
            logger.infra("Configured native targets: ${native.includedTargets.joinToString { it }}")
            when {
                ideaActive -> {
                    val hostPreset = enabledNativePresets.single { it.konanTarget == native.hostTarget }
                    logger.infra("Creating target 'native' for ${native.hostTarget.name} and linking '${hostPreset.name}Main/src'")
                    kotlin.targetFromPreset(hostPreset, "native") {

                    }
                    subproject.afterEvaluate {
                        kotlin.sourceSets.getByName("nativeMain") { sourceSet ->
                            sourceSet.kotlin.srcDir("${hostPreset.name}Main/src")
                        }
                    }
                }
                else -> {
                    val nativeMain = kotlin.sourceSets.create("nativeMain")
                    val nativeTest = kotlin.sourceSets.create("nativeTest")
                    enabledNativePresets.forEach { preset ->
                        if (preset.name !in native.includedTargets) return@forEach
                        logger.infra("Creating target '${preset.name}' with dependency on 'native'")

                        kotlin.targetFromPreset(preset) {
                            
                        }
                        kotlin.sourceSets.getByName("${preset.name}Main") { sourceSet ->
                            sourceSet.dependsOn(nativeMain)
                        }   
                        kotlin.sourceSets.getByName("${preset.name}Test") { sourceSet ->
                            sourceSet.dependsOn(nativeTest)
                        }   
                    }
                }
            }
        }
    }
}

