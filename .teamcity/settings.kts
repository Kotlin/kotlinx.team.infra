import jetbrains.buildServer.configs.kotlin.v2019_2.*
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.*
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.*

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2018.2"
val versionParameter = "releaseVersion"
val publishVersion = "0.1.0"

project {
    // Disable editing of project and build settings from the UI to avoid issues with TeamCity
    params {
        param("teamcity.ui.settings.readOnly", "true")
    }

    val build = build() 
    val deploy = deploy() 

    buildTypesOrder = listOf(build, deploy)
}

fun Project.build() = build("Build") {
    steps {
        gradle {
            name = "Build and Test Binaries"
            jdkHome = "%env.JDK_18_x64%"
            jvmArgs = "-Xmx1g"
            tasks = "clean publishToBuildRepository check"
            gradleParams = "--info --stacktrace" 
            buildFile = ""
            gradleWrapperPath = ""
        }
    }

    triggers {
        vcs {
            triggerRules = """
                    -:*.md
                    -:.gitignore
                """.trimIndent()
        }
    }

    // What files to publish as build artifacts
    artifactRules = "+:build/maven=>maven"
}

fun Project.deploy() = build("Deploy") {
    type = BuildTypeSettings.Type.DEPLOYMENT
    enablePersonalBuilds = false
    maxRunningBuilds = 1
    buildNumberPattern = "$publishVersion-dev-%build.counter%"
    params {
        param(versionParameter, "%build.number%")
        param("bintray-user", "orangy")
        password("bintray-key", "credentialsJSON:9a48193c-d16d-46c7-8751-2fb434b09e07")
    }

    vcs {
        cleanCheckout = true
    }

    steps {
        gradle {
            name = "Deploy Binaries"
            jdkHome = "%env.JDK_18_x64%"
            jvmArgs = "-Xmx1g"
            tasks = "clean build publishBintrayCreateVersion publish"
            gradleParams = "--info --stacktrace -P$versionParameter=%$versionParameter% -PbintrayApiKey=%bintray-key% -PbintrayUser=%bintray-user%"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }
}

fun Project.build(name: String, configure: BuildType.() -> Unit) = BuildType {
    // ID is prepended with Project ID, so don't repeat it here
    // ID should conform to identifier rules, so just letters, numbers and underscore
    id(name)
    // Display name of the build configuration
    this.name = name

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

fun BuildType.dependsOnSnapshot(build: BuildType, configure: SnapshotDependency.() -> Unit = {}) = apply {
    dependencies.dependency(build) {
        snapshot {
            configure()
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.CANCEL
        }
    }
}
