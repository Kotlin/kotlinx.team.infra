package kotlinx.team.infra

import org.gradle.api.*
import org.gradle.api.plugins.*
import java.io.*

class TeamCityConfiguration {
    var projectName: String? = null
    var bintrayUser: String? = null
    var bintrayToken: String? = null

    var jdk = "JDK_18_x64"
}

fun Project.configureTeamCityLogging() {
    val teamcitySuffix = project.findProperty("teamcitySuffix")?.toString()
    if (project.hasProperty("teamcity")) {
        // Tell teamcity about version number
        println("##teamcity[buildNumber '${project.version}${teamcitySuffix?.let { " ($it)" } ?: ""}']")

        gradle.taskGraph.beforeTask {
            println("##teamcity[progressMessage 'Gradle: ${it.project.path}:${it.name}']")
        }
    }
}

fun Project.configureTeamCityConfigGenerator(teamcity: TeamCityConfiguration) {
    val project = this
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
            copyResource(teamcityDir, "settings.kts") { text ->
                val projectName = teamcity.projectName ?: project.name.removeSuffix("-package")
                val projectVersion = project.version.toString()
                val bintrayUser = teamcity.bintrayUser
                    ?: throw KotlinInfrastructureException("TeamCity configuration should specify `bintrayUser` parameter")
                val bintrayToken = teamcity.bintrayToken
                    ?: throw KotlinInfrastructureException("TeamCity configuration should specify `bintrayToken` parameter")
                text
                    .replace("<<PROJECT_VERSION>>", projectVersion)
                    .replace("<<PROJECT_NAME>>", projectName)
                    .replace("<<BINTRAY_USER>>", bintrayUser)
                    .replace("<<BINTRAY_TOKEN>>", bintrayToken)
                    .replace("<<JDK>>", teamcity.jdk)
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

