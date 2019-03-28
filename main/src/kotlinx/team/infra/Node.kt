package kotlinx.team.infra

import groovy.lang.*
import kotlinx.team.infra.node.*
import org.gradle.api.*
import org.gradle.api.tasks.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import java.io.*

class MochaConfiguration {
    lateinit var version: String

    val arguments: MutableList<String> = mutableListOf()

    fun arguments(vararg value: String) {
        arguments += value
    }
}

class NodeConfiguration {
    var version = "10.15.1"
    var npmVersion = "5.7.1"

    var packages = mutableMapOf<String, String>()
    val mochaConfiguration = MochaConfiguration()

    fun install(pkg: String, version: String) {
        packages.put(pkg, version)
    }

    fun mocha(version: String) {
        install("mocha", version)
    }

    fun mocha(configureClosure: Closure<MochaConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, mochaConfiguration)

        install("mocha", mochaConfiguration.version)
    }
}

fun Project.configureNode(node: NodeConfiguration) {
    val multiplatformClass =
        tryGetClass<KotlinMultiplatformExtension>("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension")

    subprojects { subproject ->
        // Checking for MPP beforeEvaluate is too early, and in afterEvaluate too late because node plugin breaks
        subproject.pluginManager.withPlugin("kotlin-multiplatform") { plugin ->
            val multiplatform = multiplatformClass?.let { subproject.extensions.findByType(it) }
            if (multiplatform == null) {
                logger.infra("Skipping node configuration for $subproject because multiplatform plugin has not been configured properly")
                return@withPlugin
            }

            multiplatform.targets.all { target ->
                if (target.platformType == KotlinPlatformType.js) {
                    subproject.configureTarget(target, node)
                }
            }
        }
    }
}

private fun Project.configureTarget(target: KotlinTarget, node: NodeConfiguration) {
    rootProject.applyNodePlugin(node)

    val testCompilation = target.compilations.findByName("test") as? KotlinJsCompilation ?: return
    val mainCompilation = target.compilations.findByName("main") as? KotlinJsCompilation ?: return
    val targetName = target.name
    val config = NodeExtension[this]

    val dependenciesTask = task<Sync>("${targetName}TestDependencies") {
        group = "build"
        description = "Installs Node dependencies for 'test' compilation of target '$targetName'"
        dependsOn(mainCompilation.compileKotlinTask)
        dependsOn(rootProject.tasks.named("nodePrepare"))

        from(mainCompilation.compileKotlinTask.destinationDir)
        into(project.buildDir.resolve("node_modules"))

        val configuration = testCompilation.runtimeDependencyFiles
        val dependencies = configuration.files.map {
            if (it.name.endsWith(".jar")) {
                zipTree(it.absolutePath).matching {
                    include("*.js")
                    include("*.js.map")
                }
            } else {
                files(it)
            }
        }
        val dependencyFiles = files(dependencies).builtBy(configuration)
        from(dependencyFiles)
    }

    if (node.packages.contains("mocha")) {
        val mocha = createMochaTestTask(node, targetName, testCompilation, dependenciesTask)
        tasks.getByName("${targetName}Test").dependsOn(mocha)
    }
}

private fun Project.applyNodePlugin(node: NodeConfiguration) {
    // setup project with node plugin and installation tasks
    if (!plugins.hasPlugin(NodePlugin::class.java)) {
        logger.infra("Enabling node plugin in $this")
        pluginManager.apply(NodePlugin::class.java)

        NodeExtension[this].apply {
            version = node.version
            npmVersion = node.npmVersion
        }

        task<DefaultTask>("nodePrepare") {
            dependsOn(NodeSetupTask.NAME)
        }

        if (node.packages.isNotEmpty()) {
            val install = task<NpmInstallTask>("nodeInstall") {
                packages.addAll(node.packages.map { (pkg, version) -> "$pkg@$version" })
                dependsOn(NodeSetupTask.NAME)
                inputs.property("packages", node.packages)
            }
            tasks.named("nodePrepare").get().dependsOn(install)
        }
    }
}

private fun Project.createMochaTestTask(
    node: NodeConfiguration,
    targetName: String,
    testCompilation: KotlinJsCompilation,
    dependenciesTask: TaskProvider<Sync>
): TaskProvider<NodeTask> = task<NodeTask>("${targetName}TestMocha") {
    val config = NodeExtension[this@createMochaTestTask]
    val testFile = testCompilation.compileKotlinTask.outputFile
    onlyIf { testFile.exists() }
    group = "verification"
    description = "Runs tests with Mocha for 'test' compilation of target '$targetName'"
    dependsOn(dependenciesTask)
    dependsOn(testCompilation.compileKotlinTask)

    script = File(config.node_modules, "mocha/bin/mocha").absolutePath
    arguments(testFile.absolutePath, "-a", "no-sandbox")

    if (node.packages.contains("source-map-support"))
        arguments("--require", "source-map-support/register")

    if (project.hasProperty("teamcity") && node.packages.contains("mocha-teamcity-reporter"))
        arguments("--reporter", "mocha-teamcity-reporter")

    arguments(*node.mochaConfiguration.arguments.toTypedArray())

    advanced {
        it.workingDir = projectDir
    }
}
