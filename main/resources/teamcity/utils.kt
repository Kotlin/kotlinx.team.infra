/*
 * Copyright 2016-2020 JetBrains s.r.o.
 * Use of this source code is governed by the Apache 2.0 License that can be found in the LICENSE.txt file.
 */

import jetbrains.buildServer.configs.kotlin.v2019_2.*

const val versionSuffixParameter = "versionSuffix"
const val teamcitySuffixParameter = "teamcitySuffix"
const val releaseVersionParameter = "releaseVersion"

const val bintrayUserName = "<<BINTRAY_USER>>"
const val bintrayToken = "<<BINTRAY_TOKEN>>"

val platforms = Platform.values()
const val jdk = "<<JDK>>"

enum class Platform {
    Windows, Linux, MacOS;
}

fun Platform.nativeTaskPrefix(): String = when(this) {
    Platform.Windows -> "mingwX64"
    Platform.Linux -> "linuxX64"
    Platform.MacOS -> "macosX64"
}
fun Platform.buildTypeName(): String = when (this) {
    Platform.Windows, Platform.Linux -> name
    Platform.MacOS -> "Mac OS X"
}
fun Platform.buildTypeId(): String = buildTypeName().substringBefore(" ")
fun Platform.teamcityAgentName(): String = buildTypeName()


const val BUILD_CONFIGURE_VERSION_ID = "Build_Version"
const val BUILD_ALL_ID = "Build_All"
const val DEPLOY_CONFIGURE_VERSION_ID = "Deploy_Configure"
const val DEPLOY_PUBLISH_ID = "Deploy_Publish"

private fun existingBuildId(suffix: String): String = "RootProjectId_$suffix"
private fun Project.existingBuildWithId(id: String): BuildType = buildTypes.single { it.id.toString() == existingBuildId(id) }

fun Project.existingBuildVersion(): BuildType = existingBuildWithId(BUILD_CONFIGURE_VERSION_ID)
fun Project.existingBuildAll(): BuildType = existingBuildWithId(BUILD_ALL_ID)
fun Project.existingBuild(platform: Platform): BuildType = existingBuildWithId("Build_${platform.buildTypeId()}")
fun Project.existingDeployVersion(): BuildType = existingBuildWithId(DEPLOY_CONFIGURE_VERSION_ID)
fun Project.existingDeployPublish(): BuildType = existingBuildWithId(DEPLOY_PUBLISH_ID)
fun Project.existingDeploy(platform: Platform): BuildType = existingBuildWithId("Deploy_${platform.buildTypeId()}")


fun Project.buildType(name: String, platform: Platform, configure: BuildType.() -> Unit) = BuildType {
    // ID is prepended with Project ID, so don't repeat it here
    // ID should conform to identifier rules, so just letters, numbers and underscore
    id("${name}_${platform.buildTypeId()}")
    // Display name of the build configuration
    this.name = "$name (${platform.buildTypeName()})"

    requirements {
        contains("teamcity.agent.jvm.os.name", platform.teamcityAgentName())
    }

    params {
        // This parameter is needed for macOS agent to be compatible
        if (platform == Platform.MacOS) param("env.JDK_17", "")
    }

    commonConfigure()
    configure()
}.also { buildType(it) }


fun BuildType.commonConfigure() {
    requirements {
        noLessThan("teamcity.agent.hardware.memorySizeMb", "6144")
    }

    // Allow to fetch build status through API for badges
    allowExternalStatus = true

    // Configure VCS, by default use the same and only VCS root from which this configuration is fetched
    vcs {
        root(DslContext.settingsRoot)
        showDependenciesChanges = true
        checkoutMode = CheckoutMode.ON_AGENT
    }

    failureConditions {
        errorMessage = true
        nonZeroExitCode = true
        executionTimeoutMin = 120
    }

    features {
        feature {
            id = "perfmon"
            type = "perfmon"
        }
    }
}

fun BuildType.dependsOn(build: BuildType, configure: Dependency.() -> Unit) =
        apply {
            dependencies.dependency(build, configure)
        }

fun BuildType.dependsOnSnapshot(build: BuildType, onFailure: FailureAction = FailureAction.FAIL_TO_START, configure: SnapshotDependency.() -> Unit = {}) = apply {
    dependencies.dependency(build) {
        snapshot {
            configure()
            onDependencyFailure = onFailure
            onDependencyCancel = FailureAction.CANCEL
        }
    }
}