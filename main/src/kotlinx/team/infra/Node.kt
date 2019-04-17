package kotlinx.team.infra

import groovy.lang.*
import kotlinx.team.infra.node.*
import org.gradle.api.*
import org.gradle.api.artifacts.*
import org.gradle.api.artifacts.component.*
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.gradle.util.*
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.tasks.*
import java.io.*
import java.util.concurrent.*

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

        from(mainCompilation.output) {
            transformSourceMaps(mainCompilation.output.classesDirs)
        }
        from(testCompilation.output) {
            transformSourceMaps(testCompilation.output.classesDirs)
        }

        from(dependencyFiles(mainCompilation, testCompilation)) {
            val configurationName = testCompilation.runtimeDependencyConfigurationName
            val configuration = configurations.getByName(configurationName)
            val dependencyOutputFolders = discoverOutputFolders(configuration)
            transformSourceMaps(dependencyOutputFolders)
        }
        
        into(project.buildDir.resolve("node_modules"))
    }

    if (node.mochaConfiguration.version != null) {
        val mochaNode = createTestMochaNodeTask(node, targetName, testCompilation, dependenciesTask)
        tasks.getByName("${targetName}Test").dependsOn(mochaNode)
    }

    if (node.mochaConfiguration.headlessChromeVersion != null) {
        val mochaChrome = createTestMochaChromeTask(node, targetName, mainCompilation, testCompilation, dependenciesTask)
        tasks.getByName("${targetName}Test").dependsOn(mochaChrome)
    }
}

private fun Project.dependencyFiles(mainCompilation: KotlinJsCompilation, testCompilation: KotlinJsCompilation) = 
    collectDependenciesInOrder(testCompilation)
        .builtBy(mainCompilation.runtimeDependencyFiles, testCompilation.runtimeDependencyFiles)

private fun Project.dependencyAndBuiltFiles(mainCompilation: KotlinJsCompilation, testCompilation: KotlinJsCompilation) = files(
    collectDependenciesInOrder(testCompilation),
    mainCompilation.output.allOutputs.asFileTree,
    testCompilation.output.allOutputs.asFileTree
).builtBy(mainCompilation.runtimeDependencyFiles, testCompilation.runtimeDependencyFiles)

private fun Project.collectDependenciesInOrder(testCompilation: KotlinJsCompilation) = files(
    Callable {
        val name = testCompilation.runtimeDependencyConfigurationName
        val orderedDependencies = mutableSetOf<ResolvedDependency>()
        val resolvedConfiguration = configurations.getByName(name).resolvedConfiguration
        val moduleDependencies = resolvedConfiguration.firstLevelModuleDependencies
        moduleDependencies.forEach {
            collectDependencies(it, orderedDependencies)
        }

        orderedDependencies.flatMap { it.allModuleArtifacts }.map { it.file }.map { file ->
            if (file.name.endsWith(".jar")) {
                zipTree(file.absolutePath).matching {
                    it.include("*.js")
                    it.include("*.js.map")
                }
            } else {
                files(file)
            }
        }
    })

fun collectDependencies(dependency: ResolvedDependency, orderedDependencies: MutableSet<ResolvedDependency>) {
    if (!orderedDependencies.contains(dependency)) {
        dependency.children.forEach {
            collectDependencies(it, orderedDependencies)
        }
        orderedDependencies.add(dependency)
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
    arguments(testFile.absolutePath, "-a", "no-sandbox", "--full-trace")

    if (node.packages.contains("source-map-support"))
        arguments("--require", "source-map-support/register")

    configureTestMocha(node, testCompilation, dependenciesTask)
}

private fun Project.createTestMochaChromeTask(
    node: NodeConfiguration,
    targetName: String,
    mainCompilation: KotlinJsCompilation,
    testCompilation: KotlinJsCompilation,
    dependenciesTaskProvider: TaskProvider<Sync>
): TaskProvider<NodeTask> {

    return task<NodeTask>("${targetName}TestMochaChrome") {
        val config = NodeExtension[this@createTestMochaChromeTask]
        val testFile = testCompilation.compileKotlinTask.outputFile
        val mochaDir = File(config.node_modules, "mocha")
        val dependenciesTask = dependenciesTaskProvider.get()
        val dependenciesFolder = dependenciesTask.outputs.files.singleFile
        val testPage = File(dependenciesFolder, "${targetName}TestMochaChrome.html")

        doFirst {
            val dependenciesOrder = dependencyAndBuiltFiles(mainCompilation, testCompilation)
            val dependenciesIndex = dependenciesOrder.map { it.name }
            val dependencyText = dependenciesTask.outputs.files.asFileTree
                .filter {
                    it.name.endsWith(".js") && !it.name.endsWith(".meta.js")
                }
                .sortedBy {
                    val index = dependenciesIndex.indexOf(it.name)
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

private fun Project.discoverOutputFolders(configuration: Configuration) = files(Callable {
    // This is magic. I'm sure I will not understand it in a week when I come back.
    // So lets walk through…
    // Configuration dependencies are just what's specified, so we need resolvedConfiguration
    // to get what they resolved to. 
    val resolvedConfiguration = configuration.resolvedConfiguration

    // Now we need module dependencies, but only to projects.
    // This is the only way I've found to separate projects from others – by their artifact component id
    // Which is of type `ProjectComponentIdentifier`, so here we build map from module id (maven coords) to project
    val resolvedModules = resolvedConfiguration.firstLevelModuleDependencies.toMutableSet()
    val projectIds = resolvedConfiguration.resolvedArtifacts.mapNotNull {
        when (val component = it.id.componentIdentifier) {
            is ProjectComponentIdentifier ->
                it.moduleVersion.id to rootProject.project(component.projectName)
            else -> null
        }
    }.toMap()

    // Now we get all dependencies and combine them into `allModules` which is all transitive dependencies  
    val transitive = resolvedModules.flatMapTo(mutableSetOf()) { it.children }
    val allModules = (resolvedModules + transitive)

    // Now find those that are projects and select a configuration from each using resolved data 
    val resolvedProjectConfigurations = allModules.mapNotNull {
        projectIds[it.module.id]?.configurations?.getByName(it.configuration)
    }

    // Woa, we are almost there, we have configuration in the target project. 
    // Now the configuration itself provides artifacts that are JAR files already packed.
    // And it actually not the configuration itself, but something it extends and such.
    // So we need to get hierarchy of configurations, find those that can be resolved and get their task
    // dependencies. This way we find JAR tasks, but we need compile tasks! 
    // No problem, traverse task dependencies one more time! Hackery hack… 
    // 
    // Voila! Kotlin2JsCompile task is found, and we can just get its `outputFile`, to which source map is relative.
    val dependencyOutputFolders = resolvedProjectConfigurations.flatMap { config ->
        config.hierarchy.flatMap { c ->
            if (c.isCanBeResolved) {
                c.artifacts
                    .flatMap { it.buildDependencies.getDependencies(null) } // JAR tasks
                    .flatMap { it.taskDependencies.getDependencies(null) } // Compile tasks
                    .filterIsInstance<Kotlin2JsCompile>()
                    .map { it.outputFile.parentFile }
            } else
                emptyList()
        }
    }
    dependencyOutputFolders
})

/// Process .js.map files by replacing relative paths with absolute paths using provided roots
private fun AbstractCopyTask.transformSourceMaps(roots: FileCollection) {
    //println("TRANSFORM: roots: ${roots.files}")
    filesMatching("*.js.map") {
        //  println("FILE: ${it.sourcePath} (${it.relativeSourcePath})")
        it.filter { original ->
            buildString {
                var index = 0
                while (true) {
                    val beginMap = original.indexOf("\"../", index)
                    if (beginMap == -1) {
                        append(original.substring(index, original.length))
                        return@buildString
                    }
                    val endMap = original.indexOf("\"", beginMap + 1)
                    if (endMap == -1) {
                        append(original.substring(index, original.length))
                        return@buildString
                    }
                    append(original.substring(index, beginMap + 1))

                    val path = original.substring(beginMap + 1, endMap)
                    val absPath = resolveFromBases(roots, path)
                    append(absPath)
                    append("\"")
                    index = endMap + 1
                }

            }
        }
    }
}

fun resolveFromBases(files: Iterable<File>, path: String): String {
    val root = files.firstOrNull { it.resolve(path).exists() } ?: return path
    return root.resolve(path).normalize().toString()
}