package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Room über KSP (ADR 0014, Persistenz). Der Compiler läuft über KSP, nicht über kapt. */
class RoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.google.devtools.ksp")
        target.dependencies.add("implementation", target.catalog.library("androidx-room-runtime"))
        target.dependencies.add("ksp", target.catalog.library("androidx-room-compiler"))
    }
}
