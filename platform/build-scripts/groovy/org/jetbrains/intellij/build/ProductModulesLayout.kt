// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.intellij.build

import com.intellij.openapi.util.MultiValuesMap
import com.intellij.util.containers.MultiMap
import org.jetbrains.intellij.build.impl.PlatformLayout
import org.jetbrains.intellij.build.impl.PluginLayout
import java.util.function.BiConsumer

class ProductModulesLayout {
  companion object {
    @JvmStatic
    val DEFAULT_BUNDLED_PLUGINS: List<String> = listOf("intellij.platform.images")
  }

  /**
   * Name of the main product JAR file. Outputs of {@link #productImplementationModules} will be packed into it.
   */
  lateinit var mainJarName: String

  /**
   * Names of the additional product-specific modules which need to be packed into openapi.jar in the product's 'lib' directory.
   */
  var productApiModules: MutableList<String> = mutableListOf()

  /**
   * Names of the additional product-specific modules which need to be included into {@link #mainJarName} in the product's 'lib' directory
   */
  var productImplementationModules: MutableList<String> = mutableListOf()

  /**
   * Names of the main modules (containing META-INF/plugin.xml) of the plugins which need to be bundled with the product. Layouts of the
   * bundled plugins are specified in {@link #allNonTrivialPlugins} list.
   */
  var bundledPluginModules: MutableList<String> = DEFAULT_BUNDLED_PLUGINS.toMutableList()

  private var pluginsToPublish: LinkedHashSet<String> = LinkedHashSet()

  /**
   * Names of the main modules (containing META-INF/plugin.xml) of the plugins which aren't bundled with the product but may be installed
   * into it. Zip archives of these plugins will be built and placed under "&lt;product-code&gt;-plugins" directory in the build artifacts.
   * Layouts of the plugins are specified in {@link #allNonTrivialPlugins} list.
   */
  fun setPluginModulesToPublish(plugins: List<String>) {
    pluginsToPublish = LinkedHashSet(plugins)
  }

  /**
   * @see #setPluginModulesToPublish
   */
  fun getPluginModulesToPublish(): List<String> {
    return pluginsToPublish.toList()
  }

  /**
   * Describes non-trivial layout of all plugins which may be included into the product. The actual list of the plugins need to be bundled
   * with the product is specified by {@link #bundledPluginModules}, the actual list of plugins which need to be prepared for publishing
   * is specified by {@link #setPluginModulesToPublish pluginModulesToPublish}. There is no need to specify layout for plugins where it's trivial,
   * i.e. for plugins which include an output of a single module and its module libraries, it's enough to specify module names of such plugins
   * in {@link #bundledPluginModules} and {@link #setPluginModulesToPublish pluginModulesToPublish}.
   */
  var allNonTrivialPlugins: MutableList<PluginLayout> = CommunityRepositoryModules.COMMUNITY_REPOSITORY_PLUGINS.toMutableList()

  /**
   * Names of the project libraries which JARs' contents should be extracted into {@link #mainJarName} JAR.
   */
  var projectLibrariesToUnpackIntoMainJar: MutableList<String> = mutableListOf()

  /**
   * Maps names of JARs to names of the modules; these modules will be packed into these JARs and copied to the product's 'lib' directory.
   */
  var additionalPlatformJars: MultiMap<String, String> = MultiMap.createLinkedSet()

  /**
   * Module name to list of Ant-like patterns describing entries which should be excluded from its output.
   * <strong>This is a temporary property added to keep layout of some products. If some directory from a module shouldn't be included into the
   * product JAR it's strongly recommended to move that directory outside of the module source roots.</strong>
   */
  var moduleExcludes: MultiValuesMap<String, String> = MultiValuesMap<String, String>(true)

  /**
   * Additional customizations of platform JARs. <strong>This is a temporary property added to keep layout of some products.</strong>
   */
  var platformLayoutCustomizer: BiConsumer<PlatformLayout, BuildContext> = BiConsumer { _, _ -> }

  fun appendPlatformCustomizer(customizer: BiConsumer<PlatformLayout, BuildContext>) {
    val prev = platformLayoutCustomizer
    platformLayoutCustomizer = BiConsumer { layout, context ->
      prev.accept(layout, context)
      customizer.accept(layout, context)
    }
  }

  /**
   * Names of the modules which classpath will be used to build searchable options index <br>
   * //todo[nik] get rid of this property and automatically include all platform and plugin modules to the classpath when building searchable options index
   */
  var mainModules: MutableList<String> = mutableListOf()

  /**
   * If {@code true} a special xml descriptor in custom plugin repository format will be generated for {@link #setPluginModulesToPublish} plugins.
   * This descriptor and the plugin *.zip files can be uploaded to the URL specified in 'plugins@builtin-url' attribute in *ApplicationInfo.xml file
   * to allow installing custom plugins directly from IDE. If {@link ProprietaryBuildTools#artifactsServer} is specified, {@code __BUILTIN_PLUGINS_URL__} in
   * *ApplicationInfo.xml file will be automatically replaced by the plugin repository URL provided by the artifact server.
   *
   * @see #setPluginModulesToPublish
   */
  var prepareCustomPluginRepositoryForPublishedPlugins = true

  /**
   * If {@code true} then all plugins that compatible with an IDE will be built. By default these plugins will be placed to "auto-uploading"
   * subdirectory and may be automatically uploaded to plugins.jetbrains.com.
   * <br>
   * If {@code false} only plugins from {@link #setPluginModulesToPublish} will be considered.
   */
  var buildAllCompatiblePlugins = true

  /**
   * List of plugin names which should not be built even if they are compatible and {@link #buildAllCompatiblePlugins} is true
   */
  var compatiblePluginsToIgnore: MutableList<String> = mutableListOf()

  /**
   * Module names which should be excluded from this product.
   * Allows to filter out default platform modules (both api and implementation) as well as product modules.
   * This API is experimental, use with care
   */
  var excludedModuleNames: MutableSet<String> = mutableSetOf()

  /**
   * @return list of all modules which output is included into the plugin's JARs
   */
  fun getIncludedPluginModules(enabledPluginModules: Set<String>): Collection<String> {
    val result = LinkedHashSet<String>()
    result.addAll(enabledPluginModules)
    result.addAll(allNonTrivialPlugins
                    .filter { enabledPluginModules.contains(it.mainModule) }
                    .flatMap { it.getIncludedModuleNames() })
    return result
  }

  /**
   * Map name of JAR to names of the modules; these modules will be packed into these JARs and copied to the product's 'lib' directory.
   */
  fun withAdditionalPlatformJar(jarName: String, vararg moduleNames: String) {
    additionalPlatformJars.putValues(jarName, moduleNames.toList())
  }

  fun withoutAdditionalPlatformJar(jarName: String, moduleName: String) {
    additionalPlatformJars.remove(jarName, moduleName)
  }
}