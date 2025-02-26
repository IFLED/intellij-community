// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.configuration

import com.intellij.jarRepository.RepositoryLibraryType
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import org.jetbrains.annotations.Nls
import org.jetbrains.idea.maven.utils.library.RepositoryLibraryProperties
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.idea.KotlinJvmBundle
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.facet.getOrCreateFacet
import org.jetbrains.kotlin.idea.facet.initializeIfNeeded
import org.jetbrains.kotlin.idea.framework.JavaRuntimeDetectionUtil
import org.jetbrains.kotlin.idea.framework.JavaRuntimeLibraryDescription
import org.jetbrains.kotlin.idea.framework.JsLibraryStdDetectionUtil
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.idea.util.projectStructure.sdk
import org.jetbrains.kotlin.idea.util.projectStructure.version
import org.jetbrains.kotlin.idea.versions.LibraryJarDescriptor
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms

open class KotlinJavaModuleConfigurator : KotlinWithLibraryConfigurator<RepositoryLibraryProperties>() {
    override fun isApplicable(module: Module): Boolean {
        return super.isApplicable(module) && !hasBrokenJsRuntime(module)
    }

    override val libraryType: RepositoryLibraryType get() = RepositoryLibraryType.getInstance()

    override val libraryProperties: RepositoryLibraryProperties get() = libraryJarDescriptor.repositoryLibraryProperties

    override fun isConfigured(module: Module): Boolean {
        return hasKotlinJvmRuntimeInScope(module)
    }

    override val libraryName: String
        get() = JavaRuntimeLibraryDescription.LIBRARY_NAME

    override val dialogTitle: String
        get() = JavaRuntimeLibraryDescription.DIALOG_TITLE

    override val messageForOverrideDialog: String
        @Nls
        get() = JavaRuntimeLibraryDescription.JAVA_RUNTIME_LIBRARY_CREATION

    override val presentableText: String
        get() = KotlinJvmBundle.message("language.name.java")

    override val name: String
        get() = NAME

    override val targetPlatform: TargetPlatform
        get() = JvmPlatforms.unspecifiedJvmPlatform

    override val libraryJarDescriptor get() = LibraryJarDescriptor.RUNTIME_JDK8_JAR

    override val libraryMatcher: (Library, Project) -> Boolean =
        { library, _ -> JavaRuntimeDetectionUtil.getRuntimeJar(library.getFiles(OrderRootType.CLASSES).asList()) != null }

    override fun configureKotlinSettings(modules: List<Module>) {
        val project = modules.firstOrNull()?.project ?: return
        val canChangeProjectSettings = project.allModules().all {
            it.sdk?.version?.isAtLeast(JavaSdkVersion.JDK_1_8) ?: true
        }
        if (canChangeProjectSettings) {
            Kotlin2JvmCompilerArgumentsHolder.getInstance(project).update {
                jvmTarget = "1.8"
            }
        } else {
            for (module in modules) {
                val sdkVersion = module.sdk?.version
                if (sdkVersion != null && sdkVersion.isAtLeast(JavaSdkVersion.JDK_1_8)) {
                    val modelsProvider = ProjectDataManager.getInstance().createModifiableModelsProvider(project)
                    try {
                        val facet = module.getOrCreateFacet(modelsProvider, useProjectSettings = false, commitModel = true)
                        val facetSettings = facet.configuration.settings
                        facetSettings.initializeIfNeeded(module, null, JvmPlatforms.jvm8)
                        (facetSettings.compilerArguments as? K2JVMCompilerArguments)?.jvmTarget = "1.8"
                    } finally {
                        modelsProvider.dispose()
                    }
                }
            }
        }
    }

    override fun configureModule(module: Module, collector: NotificationMessageCollector, writeActions: MutableList<() -> Unit>?) {
        super.configureModule(module, collector, writeActions)
        addStdlibToJavaModuleInfo(module, collector, writeActions)
    }

    companion object {
        const val NAME = "java"

        val instance: KotlinJavaModuleConfigurator
            get() {
                @Suppress("DEPRECATION")
                return Extensions.findExtension(KotlinProjectConfigurator.EP_NAME, KotlinJavaModuleConfigurator::class.java)
            }
    }

    private fun hasBrokenJsRuntime(module: Module): Boolean {
        for (orderEntry in ModuleRootManager.getInstance(module).orderEntries) {
            val library = (orderEntry as? LibraryOrderEntry)?.library as? LibraryEx ?: continue
            if (JsLibraryStdDetectionUtil.hasJsStdlibJar(library, module.project, ignoreKind = true)) return true
        }
        return false
    }
}
