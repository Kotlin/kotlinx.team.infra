package kotlinx.team.infra

import org.gradle.api.*
import java.io.*

class TeamCityConfiguration {
    var projectName: String? = null
    var version: String? = null
}

fun Project.configureTeamCity(teamcity: TeamCityConfiguration) {
    val project = this
    task<DefaultTask>("setupTeamCity") {
        doLast {
            val teamcityDir = projectDir.resolve(".teamcity")
            logger.infra("Setting up TeamCity configuration at $teamcityDir")
            teamcityDir.mkdir()
            copyResource(teamcityDir, "pom.xml") {
                it
                    .replace("<artifactId>resource</artifactId>", "<artifactId>teamcity</artifactId>")
            }
            copyResource(teamcityDir, "settings.kts") {
                it
                    .replace("<<VERSION>>", teamcity.version ?: project.version.toString())
                    .replace("<<NAME>>", teamcity.projectName ?: project.name.removeSuffix("-package"))
            }
        }
    }
}

private inline fun copyResource(teamcityDir: File, file: String, transform: (String) -> String = { it }) {
    val resource = InfraPlugin::class.java.getResourceAsStream("/teamcity/$file")
        ?: throw KotlinInfrastructureException("Cannot find resource for teamcity file $file")

    resource.bufferedReader().use { input ->
        val text = input.readText().let(transform)
        teamcityDir.resolve(file).writeText(text)
    }
}

