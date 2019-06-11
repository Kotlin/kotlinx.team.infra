package kotlinx.team.infra

import groovy.lang.*
import kotlinx.team.infra.api.*
import org.gradle.api.*
import org.gradle.util.*

open class InfraExtension(val project: Project) {
    val publishing = PublishingConfiguration()
    private var publishingHandler: ((PublishingConfiguration) -> Unit)? = null
    internal fun afterPublishing(handler: (PublishingConfiguration) -> Unit) {
        publishingHandler = handler
    }

    fun publishing(configureClosure: Closure<PublishingConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, publishing)
        publishingHandler?.invoke(publishing)
    }    
    
    val teamcity = TeamCityConfiguration()
    private var teamcityHandler: ((TeamCityConfiguration) -> Unit)? = null
    internal fun afterTeamCity(handler: (TeamCityConfiguration) -> Unit) {
        teamcityHandler = handler
    }

    fun teamcity(configureClosure: Closure<TeamCityConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, teamcity)
        teamcityHandler?.invoke(teamcity)
    }

    val apiCheck = ApiCheckConfiguration()
    private var apiCheckHandler: ((ApiCheckConfiguration) -> Unit)? = null
    internal fun afterApiCheck(handler: (ApiCheckConfiguration) -> Unit) {
        apiCheckHandler = handler
    }

    fun apiCheck(configureClosure: Closure<ApiCheckConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, apiCheck)
        apiCheckHandler?.invoke(apiCheck)
    }
}


