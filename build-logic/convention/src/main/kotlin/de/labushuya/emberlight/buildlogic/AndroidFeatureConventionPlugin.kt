package de.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/** Feature-Modul: Library, Compose, Hilt und Navigation. Hängt von `:core:design` ab. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("emberlight.android.library")
        target.pluginManager.apply("emberlight.android.library.compose")
        target.pluginManager.apply("emberlight.hilt")

        val navigation = target.catalog.library("androidx-navigation-compose")
        val hiltNavigation = target.catalog.library("androidx-hilt-navigation-compose")
        target.dependencies {
            add("implementation", project(":core:design"))
            add("implementation", navigation)
            add("implementation", hiltNavigation)
        }
    }
}
