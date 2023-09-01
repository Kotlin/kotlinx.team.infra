package kotlinx.team.infra

import org.gradle.api.*
import org.gradle.api.logging.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*

internal inline fun <reified T : Task> Project.task(
    name: String,
    noinline configuration: T.() -> Unit
): TaskProvider<T> =
    tasks.register(name, T::class.java, Action(configuration))

internal fun <T> Project.tryGetClass(className: String): Class<T>? {
    val classLoader = buildscript.classLoader
    return try {
        @Suppress("UNCHECKED_CAST")
        Class.forName(className, false, classLoader) as Class<T>
    } catch (e: ClassNotFoundException) {
        null
    }
}

internal fun Logger.infra(message: String) {
    info("INFRA: $message")
}

internal fun Project.propertyOrEnv(name: String): String? =
    findProperty(name) as? String ?: System.getenv(name)

internal inline fun <reified T: Any> ObjectFactory.newInstance(): T = newInstance(T::class.java)