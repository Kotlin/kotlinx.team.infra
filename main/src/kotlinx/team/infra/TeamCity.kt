package kotlinx.team.infra

import org.gradle.api.*
import java.io.*

open class TeamCityConfiguration {
    @Deprecated("Configure libraryStagingRepoDescription in infra/publishing/sonatype")
    var libraryStagingRepoDescription: String? = null

    var jdk = "JDK_18"

    /**
     * Specifies whether to override the build number in TeamCity, `true` by default.
     *
     * If `true`, the TeamCity build number will be changed to `"${project.version} (%build.counter%)"`.
     * This value is overridden by `overrideTeamCityBuildNumber` gradle property when provided.
     */
    var overrideBuildNumber = true
}

fun Project.configureTeamCityLogging() {
    if (project.hasProperty("teamcity")) {
        gradle.taskGraph.beforeTask {
            println("##teamcity[progressMessage 'Gradle: ${this.project.path}:${this.name}']")
        }
    }
}

fun Project.configureTeamcityBuildNumber(teamcity: TeamCityConfiguration) {
    val overrideTeamCityBuildNumber = project.findProperty("overrideTeamCityBuildNumber")?.toString()?.toBoolean()
        ?: teamcity.overrideBuildNumber
    if (project.hasProperty("teamcity") && overrideTeamCityBuildNumber) {
        // Tell teamcity about version number
        val teamcitySuffix = project.findProperty("teamcitySuffix")?.toString()
        println("##teamcity[buildNumber '${project.version}${teamcitySuffix?.let { " ($it)" } ?: ""}']")
    }
}

fun Project.configureTeamCityConfigGenerator(teamcity: TeamCityConfiguration, publishing: PublishingConfiguration) {
    task<DefaultTask>("setupTeamCity") {
        group = "build setup"
        description = "Generates TeamCity project build configuration scripts"
        doLast {
            val teamcityDir = projectDir.resolve(".teamcity")
            logger.infra("Setting up TeamCity configuration at $teamcityDir")
            teamcityDir.mkdir()
            copyResource(teamcityDir, "pom.xml") {
                it
                    .replace("<artifactId>resource</artifactId>", "<artifactId>teamcity</artifactId>")
            }
            @Suppress("DEPRECATION")
            copyResource(teamcityDir, "utils.kt") { text ->
                val libraryStagingRepoDescription = publishing.sonatype.libraryStagingRepoDescription ?: teamcity.libraryStagingRepoDescription
                    ?: throw KotlinInfrastructureException("TeamCity configuration should specify `libraryStagingRepoDescription`: the library description for staging repositories")
                text
                    .replace("<<LIBRARY_STAGING_REPO_DESCRIPTION>>", libraryStagingRepoDescription)
                    .replace("<<JDK>>", teamcity.jdk)
                    .replace("<<SINGLE_AGENT_MAC_DEPLOYMENT>>", publishing.singleAgentMacDeployment.toString())
            }
            copyResource(teamcityDir, "additionalConfiguration.kt", override = false)
            copyResource(teamcityDir, "settings.kts")
        }
    }
}

private inline fun copyResource(teamcityDir: File, file: String, override: Boolean = true, transform: (String) -> String = { it }) {
    val resource = InfraPlugin::class.java.getResourceAsStream("/teamcity/$file")
        ?: throw KotlinInfrastructureException("Cannot find resource for teamcity file $file")
    val destinationFile = teamcityDir.resolve(file)

    if (!override && destinationFile.exists()) return

    resource.bufferedReader().use { input ->
        val text = input.readText().let(transform)
        destinationFile.writeText(text)
    }
}

