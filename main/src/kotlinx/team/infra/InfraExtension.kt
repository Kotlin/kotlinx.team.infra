package kotlinx.team.infra

import org.gradle.api.*

open class InfraExtension(val project: Project) {
    val publishing = project.objects.newInstance<PublishingConfiguration>()
    private var publishingHandler: ((PublishingConfiguration) -> Unit)? = null
    internal fun afterPublishing(handler: (PublishingConfiguration) -> Unit) {
        publishingHandler = handler
    }

    fun publishing(configure: Action<PublishingConfiguration>) {
        configure.execute(publishing)
        publishingHandler?.invoke(publishing)
    }

    val teamcity = project.objects.newInstance<TeamCityConfiguration>()
    private var teamcityHandler: ((TeamCityConfiguration) -> Unit)? = null
    internal fun afterTeamCity(handler: (TeamCityConfiguration) -> Unit) {
        teamcityHandler = handler
    }

    fun teamcity(configure: Action<TeamCityConfiguration>) {
        configure.execute(teamcity)
        teamcityHandler?.invoke(teamcity)
    }
}


