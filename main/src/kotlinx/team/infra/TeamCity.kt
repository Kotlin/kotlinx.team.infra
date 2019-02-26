package kotlinx.team.infra

import org.gradle.api.*
import java.io.*

class TeamCityConfiguration {
    var projectName: String? = null
    var bintrayUser: String? = null
    var bintrayToken: String? = null
}

fun Project.configureTeamCity(teamcity: TeamCityConfiguration) {
    if (project.hasProperty("teamcity")) {
        val releaseVersion = project.findProperty("releaseVersion")?.toString()
        project.version = if (releaseVersion != null && releaseVersion.isNotEmpty()) {
            releaseVersion
        } else {
            // Configure version
            val versionSuffix = project.findProperty("versionSuffix")?.toString()
            if (versionSuffix != null && versionSuffix.isNotEmpty())
                "${project.version}-$versionSuffix>']"
            else
                project.version.toString()
        }

        logger.infra("Configured root project version as '${project.version}'")

        // Tell teamcity about version number
        println("##teamcity[buildNumber '<${project.version}>']")
    }

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
                val projectName = teamcity.projectName ?: project.name.removeSuffix("-package")
                val projectVersion = project.version.toString()
                val bintrayUser = teamcity.bintrayUser
                    ?: throw KotlinInfrastructureException("TeamCity configuration should specify `bintrayUser` parameter")
                val bintrayToken = teamcity.bintrayToken
                    ?: throw KotlinInfrastructureException("TeamCity configuration should specify `bintrayToken` parameter")
                it
                    .replace("<<PROJECT_VERSION>>", projectVersion)
                    .replace("<<PROJECT_NAME>>", projectName)
                    .replace("<<BINTRAY_USER>>", bintrayUser)
                    .replace("<<BINTRAY_TOKEN>>", bintrayToken)
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

