package kotlinx.team.infra.api

import org.gradle.api.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.renderer.*
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*
import org.jetbrains.kotlin.resolve.scopes.*
import java.io.*

class ModuleDescriptorApiGenerator(val project: Project, private val outputDir: File, private val onlyPublic: Boolean = true) {
    private val renderer = DescriptorRenderer.withOptions {
        actualPropertiesInPrimaryConstructor = true
        alwaysRenderModifiers = true
        classifierNamePolicy = ClassifierNamePolicy.FULLY_QUALIFIED
        eachAnnotationOnNewLine = true
        includePropertyConstant = true
        normalizedVisibilities = true
        parameterNameRenderingPolicy = ParameterNameRenderingPolicy.ALL
        renderCompanionObjectName = true
        renderDefaultModality = true
        renderDefaultVisibility = true
        renderConstructorKeyword = true
        withDefinedIn = false
        overrideRenderingPolicy = OverrideRenderingPolicy.RENDER_OPEN_OVERRIDE
    }

    fun generate(module: ModuleDescriptor) {
        val text = buildString {
            processPackage(module, module.getPackage(FqName.ROOT))
        }
        val fileName = module.name.asString().removeSurrounding("<", ">")
        outputDir.resolve("$fileName.api").writeText(text)
    }

    private fun Appendable.processPackage(module: ModuleDescriptor, packageView: PackageViewDescriptor) {
        val fragments = packageView.fragments.filter { it.module == module }
        val unified = fragments.groupBy { it.fqName }
        for ((name, parts) in unified) {
            appendln("package $name")
            appendln("{")
            parts.forEach { packageFragment ->
                val allDescriptors = DescriptorUtils.getAllDescriptors(packageFragment.getMemberScope())
                val classDescriptors = allDescriptors.filter { it is ClassifierDescriptor }
                val callableDescriptors = allDescriptors.filter { it is CallableDescriptor }
                (classDescriptors + callableDescriptors)
                    .filter { !onlyPublic || (it is MemberDescriptor && it.isEffectivelyPublicApi) }
                    .forEach { generateEntity("   ", it) }
            }
            appendln("}")
            appendln()
        }

        for (subpackageName in module.getSubPackagesOf(
            packageView.fqName,
            MemberScope.ALL_NAME_FILTER
        )) {
            processPackage(module, module.getPackage(subpackageName))
        }
    }

    private fun Appendable.generateEntity(indent: String, descriptor: DeclarationDescriptor) {
        when (descriptor) {
            is FunctionDescriptor -> appendFunction(indent, descriptor)
            is ClassDescriptor -> generateClass(indent, descriptor)
            is PropertyDescriptor -> appendProperty(indent, descriptor)
            else -> appendln("$indent${renderer.render(descriptor)}")
        }
    }

    private fun Appendable.generateClass(indent: String, descriptor: ClassDescriptor) {
        appendln("$indent${renderer.render(descriptor)}")
        if (descriptor !is EnumEntrySyntheticClassDescriptor)
            appendClassBody(indent, descriptor)
        appendln()
    }


    private fun Appendable.appendClassBody(indent: String, descriptor: ClassDescriptor) {
        val builtins = descriptor.module.builtIns
        appendln("$indent{")
        val members = DescriptorUtils.getAllDescriptors(descriptor.unsubstitutedMemberScope) + descriptor.constructors
        members
            .filter { !onlyPublic || (it is MemberDescriptor && it.isEffectivelyPublicApi) }
            .filter { !builtins.isMemberOfAny(it) }
            .forEach { generateEntity("$indent    ", it) }

        appendln("$indent}")
    }

    private fun Appendable.appendFunction(indent: String, descriptor: FunctionDescriptor) {
        appendln("$indent${renderer.render(descriptor)}")
        appendln()
    }

    private fun Appendable.appendProperty(indent: String, descriptor: PropertyDescriptor) {
        val setter = descriptor.setter?.let {
            if (!onlyPublic || it.isEffectivelyPublicApi) it else null
        }

        val getter = descriptor.getter?.let {
            if (!onlyPublic || it.isEffectivelyPublicApi) it else null
        }

        append("$indent${renderer.render(descriptor)}")
        if (getter != null || setter != null) {
            append(" { ")
            if (getter != null)
                append("get;")
            if (getter != null && setter != null)
                append(" ")
            if (setter != null)
                append("set;")
            append(" }")
        }
        appendln()
    }
}