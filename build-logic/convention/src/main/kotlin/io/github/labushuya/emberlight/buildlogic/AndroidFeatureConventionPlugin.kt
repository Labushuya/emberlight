package io.github.labushuya.emberlight.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Feature-Modul: Library, Compose, Hilt, Navigation und typsichere Routen (kotlinx.serialization).
 * Hängt von `:core:design` ab. Robolectric-Tests laufen mit Android-Ressourcen.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("emberlight.android.library")
        target.pluginManager.apply("emberlight.android.library.compose")
        target.pluginManager.apply("emberlight.hilt")
        target.pluginManager.apply(target.catalog.plugin("kotlin-serialization").get().pluginId)

        target.extensions.configure<LibraryExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        val navigation = target.catalog.library("androidx-navigation-compose")
        val hiltNavigation = target.catalog.library("androidx-hilt-navigation-compose")
        val robolectric = target.catalog.library("robolectric")
        val androidxTestJunit = target.catalog.library("androidx-test-ext-junit")
        target.dependencies {
            add("implementation", project(":core:design"))
            add("implementation", navigation)
            add("implementation", hiltNavigation)
            add("testImplementation", robolectric)
            add("testImplementation", androidxTestJunit)
        }
    }
}
