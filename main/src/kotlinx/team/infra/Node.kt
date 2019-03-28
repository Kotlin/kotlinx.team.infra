package kotlinx.team.infra

import groovy.lang.*
import kotlinx.team.infra.node.*
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import java.io.*

class MochaConfiguration {
    var version: String? = null
    var headlessChromeVersion: String? = null

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

    fun mocha(version: String) = mocha(closureOf { this.version = version })

    fun mocha(configureClosure: Closure<MochaConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, mochaConfiguration)

        val mochaVersion =
            mochaConfiguration.version ?: throw KotlinInfrastructureException("Mocha Version has not been specified")
        install("mocha", mochaVersion)

        mochaConfiguration.headlessChromeVersion?.let {
            install("mocha-headless-chrome", it)
        }
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
        from(dependencyFiles(testCompilation))
        into(project.buildDir.resolve("node_modules"))
    }

    if (node.mochaConfiguration.version != null) {
        val mochaNode = createTestMochaNodeTask(node, targetName, testCompilation, dependenciesTask)
        tasks.getByName("${targetName}Test").dependsOn(mochaNode)
    }

    if (node.mochaConfiguration.headlessChromeVersion != null) {
        val mochaChrome = createTestMochaChromeTask(node, targetName, testCompilation, dependenciesTask)
        tasks.getByName("${targetName}Test").dependsOn(mochaChrome)
    }
}

private fun Project.dependencyFiles(compilation: KotlinJsCompilation): ConfigurableFileCollection {
    val name = compilation.runtimeDependencyConfigurationName

    val resolvedDependencies = mutableSetOf<ResolvedDependency>()

    val moduleDependencies = configurations.getByName(name).resolvedConfiguration.firstLevelModuleDependencies
    
    moduleDependencies.forEach {
        collectDependencies(it, resolvedDependencies)
    }

    val configuration = compilation.runtimeDependencyFiles

    val dependencies = resolvedDependencies.flatMap { it.allModuleArtifacts }.map { it.file }.map {
        if (it.name.endsWith(".jar")) {
            zipTree(it.absolutePath).matching {
                it.include("*.js")
                it.include("*.js.map")
            }
        } else {
            files(it)
        }
    }
    
    return files(dependencies, compilation.output.allOutputs.files).builtBy(configuration)
}

fun collectDependencies(dependency: ResolvedDependency, resolvedDependencies: MutableSet<ResolvedDependency>) {
    if (!resolvedDependencies.contains(dependency)) {
        dependency.children.forEach {
            collectDependencies(it, resolvedDependencies)
        }
        resolvedDependencies.add(dependency)
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

private fun Project.createTestMochaNodeTask(
    node: NodeConfiguration,
    targetName: String,
    testCompilation: KotlinJsCompilation,
    dependenciesTask: TaskProvider<Sync>
): TaskProvider<NodeTask> = task<NodeTask>("${targetName}TestMochaNode") {

    val config = NodeExtension[this@createTestMochaNodeTask]
    val testFile = testCompilation.compileKotlinTask.outputFile
    onlyIf { testFile.exists() }
    group = "verification"
    description = "Runs tests with Mocha/Node for 'test' compilation of target '$targetName'"

    script = File(config.node_modules, "mocha/bin/mocha").absolutePath
    arguments(testFile.absolutePath, "-a", "no-sandbox")

    if (node.packages.contains("source-map-support"))
        arguments("--require", "source-map-support/register")

    configureTestMocha(node, testCompilation, dependenciesTask)
}

private fun Project.createTestMochaChromeTask(
    node: NodeConfiguration,
    targetName: String,
    testCompilation: KotlinJsCompilation,
    dependenciesTaskProvider: TaskProvider<Sync>
): TaskProvider<NodeTask> {

    return task<NodeTask>("${targetName}TestMochaChrome") {
        val config = NodeExtension[this@createTestMochaChromeTask]
        val testFile = testCompilation.compileKotlinTask.outputFile
        val mochaDir = File(config.node_modules, "mocha")
        val dependenciesTask = dependenciesTaskProvider.get()
        val dependenciesOrder = dependencyFiles(testCompilation).map { it.name }
        val dependenciesFolder = dependenciesTask.outputs.files.singleFile
        val testPage = File(dependenciesFolder, "${targetName}TestMochaChrome.html")

        doFirst {
            val dependencyText = dependenciesTask.outputs.files.asFileTree
                .filter {
                    it.name.endsWith(".js") && !it.name.endsWith(".meta.js")
                }
                .sortedBy { 
                    val index = dependenciesOrder.indexOf(it.name)
                    if (index == -1)
                        10000
                    else
                        index
                }
                .joinToString("\n") {
                    "    <script src=\"${it.relativeTo(dependenciesFolder)}\"></script>"
                }

            testPage.writeText(
                """
<!DOCTYPE html>
<html>
<head>
    <title>Mocha Tests</title>
    <meta charset="utf-8">
    <link rel="stylesheet" href="${mochaDir.relativeTo(dependenciesFolder)}/mocha.css">
</head>
<body>
    <div id="mocha"></div>
    <script src="${mochaDir.relativeTo(dependenciesFolder)}/mocha.js"></script>
    <script>mocha.timeout(10000000);</script>
    <script>mocha.setup('bdd');</script>

$dependencyText 

    <script>mocha.run();</script>
    </body>
</html>
"""
            )
        }

        onlyIf { testFile.exists() }
        group = "verification"
        description = "Runs tests with Mocha/Chrome for 'test' compilation of target '$targetName'"

        script = File(config.node_modules, "mocha-headless-chrome/bin/start").absolutePath
        arguments(testFile.absolutePath, "--file", testPage.absolutePath)

        configureTestMocha(node, testCompilation, dependenciesTaskProvider)
    }
}

private fun NodeTask.configureTestMocha(
    node: NodeConfiguration,
    testCompilation: KotlinJsCompilation,
    dependenciesTask: TaskProvider<Sync>
) {
    dependsOn(dependenciesTask)
    dependsOn(testCompilation.compileKotlinTask)

    if (project.hasProperty("teamcity") && node.packages.contains("mocha-teamcity-reporter"))
        arguments("--reporter", "mocha-teamcity-reporter")

    arguments(*node.mochaConfiguration.arguments.toTypedArray())

    advanced {
        it.workingDir = project.projectDir
    }
}
