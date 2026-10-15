package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Hilt über KSP (kein kapt). */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.google.devtools.ksp")
        target.pluginManager.apply("com.google.dagger.hilt.android")

        val hiltAndroid = target.catalog.library("hilt-android")
        val hiltCompiler = target.catalog.library("hilt-compiler")
        target.dependencies.add("implementation", hiltAndroid)
        target.dependencies.add("ksp", hiltCompiler)
    }
}
