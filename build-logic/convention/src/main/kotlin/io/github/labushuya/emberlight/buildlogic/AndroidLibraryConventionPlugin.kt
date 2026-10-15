package io.github.labushuya.emberlight.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.library")

        target.extensions.configure<LibraryExtension> {
            // Namespace leitet sich aus dem Modulpfad ab, z. B. :core:design -> io.github.labushuya.emberlight.core.design
            namespace = "${EmberlightConfig.APPLICATION_ID}${target.path.replace(':', '.')}"
            configureSdkLevels()
            compileOptions { useJava17() }
            lint { applyEmberlightDefaults(checkDependencies = false) }
        }

        target.configureKotlinAndroid()
        target.configureUnitTestDependencies()
    }
}
