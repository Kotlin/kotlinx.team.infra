package kotlinx.team.infra

import groovy.lang.*
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
    
    
    val node = NodeConfiguration()
    private var nodeHandler: ((NodeConfiguration) -> Unit)? = null
    internal fun afterNode(handler: (NodeConfiguration) -> Unit) {
        nodeHandler = handler
    }

    fun node(configureClosure: Closure<NodeConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, node)
        nodeHandler?.invoke(node)
    }
    
    val nativeTargets = NativeConfiguration()
    private var nativeTargetsHandler: ((NativeConfiguration) -> Unit)? = null
    internal fun afterNativeTargets(handler: (NativeConfiguration) -> Unit) {
        nativeTargetsHandler = handler
    }

    fun nativeTargets(configureClosure: Closure<NativeConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, nativeTargets)
        nativeTargetsHandler?.invoke(nativeTargets)
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
}


