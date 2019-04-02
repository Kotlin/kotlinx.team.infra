/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlinx.team.infra.api.jvm

import kotlinx.metadata.jvm.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.*

fun ClassNode.toClassVisibility() = loadKotlinMetadata()?.toClassVisibility(name)

fun ClassNode.loadKotlinMetadata(): KotlinClassMetadata? {
    val metadata = findAnnotation("kotlin/Metadata", false) ?: return null
    @Suppress("UNCHECKED_CAST")
    val header = with(metadata) {
        KotlinClassHeader(
            kind = get("k") as Int?,
            metadataVersion = (get("mv") as List<Int>?)?.toIntArray(),
            bytecodeVersion = (get("bv") as List<Int>?)?.toIntArray(),
            data1 = (get("d1") as List<String>?)?.toTypedArray(),
            data2 = (get("d2") as List<String>?)?.toTypedArray(),
            extraString = get("xs") as String?,
            packageName = get("pn") as String?,
            extraInt = get("xi") as Int?
        )
    }
    return KotlinClassMetadata.read(header)
}

val ACCESS_NAMES = mapOf(
    Opcodes.ACC_PUBLIC to "public",
    Opcodes.ACC_PROTECTED to "protected",
    Opcodes.ACC_PRIVATE to "private",
    Opcodes.ACC_STATIC to "static",
    Opcodes.ACC_FINAL to "final",
    Opcodes.ACC_ABSTRACT to "abstract",
    Opcodes.ACC_SYNTHETIC to "synthetic",
    Opcodes.ACC_INTERFACE to "interface",
    Opcodes.ACC_ANNOTATION to "annotation"
)

fun isPublic(access: Int) = access and Opcodes.ACC_PUBLIC != 0 || access and Opcodes.ACC_PROTECTED != 0
fun isProtected(access: Int) = access and Opcodes.ACC_PROTECTED != 0
fun isStatic(access: Int) = access and Opcodes.ACC_STATIC != 0
fun isFinal(access: Int) = access and Opcodes.ACC_FINAL != 0
fun isSynthetic(access: Int) = access and Opcodes.ACC_SYNTHETIC != 0


fun ClassNode.isEffectivelyPublic(classVisibility: ClassVisibility?) =
    isPublic(access)
            && !isLocal()
            && !isWhenMappings()
            && (classVisibility?.isPublic(isPublishedApi()) ?: true)


val ClassNode.innerClassNode: InnerClassNode? get() = innerClasses.singleOrNull { it.name == name }
fun ClassNode.isLocal() = innerClassNode?.run { innerName == null && outerName == null} ?: false
fun ClassNode.isInner() = innerClassNode != null
fun ClassNode.isWhenMappings() = isSynthetic(access) && name.endsWith("\$WhenMappings")

val ClassNode.effectiveAccess: Int get() = innerClassNode?.access ?: access
val ClassNode.outerClassName: String? get() = innerClassNode?.outerName


const val publishedApiAnnotationName = "kotlin/PublishedApi"
fun ClassNode.isPublishedApi() = findAnnotation(publishedApiAnnotationName, includeInvisible = true) != null
fun MethodNode.isPublishedApi() = findAnnotation(publishedApiAnnotationName, includeInvisible = true) != null
fun FieldNode.isPublishedApi() = findAnnotation(publishedApiAnnotationName, includeInvisible = true) != null


fun ClassNode.isDefaultImpls(metadata: KotlinClassMetadata?) = isInner() && name.endsWith("\$DefaultImpls") && metadata.isSyntheticClass()


fun ClassNode.findAnnotation(annotationName: String, includeInvisible: Boolean = false) = findAnnotation(annotationName, visibleAnnotations, invisibleAnnotations, includeInvisible)
fun MethodNode.findAnnotation(annotationName: String, includeInvisible: Boolean = false) = findAnnotation(annotationName, visibleAnnotations, invisibleAnnotations, includeInvisible)
fun FieldNode.findAnnotation(annotationName: String, includeInvisible: Boolean = false) = findAnnotation(annotationName, visibleAnnotations, invisibleAnnotations, includeInvisible)

operator fun AnnotationNode.get(key: String): Any? = values.annotationValue(key)

private fun List<Any>.annotationValue(key: String): Any? {
    for (index in (0 until size / 2)) {
        if (this[index * 2] == key)
            return this[index * 2 + 1]
    }
    return null
}

private fun findAnnotation(annotationName: String, visibleAnnotations: List<AnnotationNode>?, invisibleAnnotations: List<AnnotationNode>?, includeInvisible: Boolean): AnnotationNode? =
    visibleAnnotations?.firstOrNull { it.refersToName(annotationName) }
            ?: if (includeInvisible) invisibleAnnotations?.firstOrNull { it.refersToName(annotationName) } else null

fun AnnotationNode.refersToName(name: String) = desc.startsWith('L') && desc.endsWith(';') && desc.regionMatches(1, name, 0, name.length)


fun Sequence<ClassNode>.readKotlinVisibilities(): Map<String, ClassVisibility> =
    mapNotNull { it.toClassVisibility() }
        .associateBy { it.name }
        .apply {
            values.asSequence().filter { it.isCompanion }.forEach {
                val containingClassName = it.name.substringBeforeLast('$')
                getValue(containingClassName).companionVisibilities = it
            }

            values.asSequence().filter { it.facadeClassName != null }.forEach {
                getValue(it.facadeClassName!!).partVisibilities.add(it)
            }
        }
