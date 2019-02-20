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
}

open class PublishingConfiguration {
    val bintray = BintrayConfiguration()
    fun bintray(configureClosure: Closure<BintrayConfiguration>) {
        ConfigureUtil.configureSelf(configureClosure, bintray)
        bintrayHandler?.invoke(bintray)
    }

    private var bintrayHandler: ((BintrayConfiguration) -> Unit)? = null
    internal fun afterBintray(handler: (BintrayConfiguration) -> Unit) {
        bintrayHandler = handler
    }

    var includeProjects: MutableList<String> = mutableListOf()
    fun include(name: String) {
        includeProjects.add(name)
    }
}

open class BintrayConfiguration {
    var username: String? = null
    var password: String? = null

    var organization: String? = null
    var repository: String? = null
    var library: String? = null

    var publish: Boolean = false

}
