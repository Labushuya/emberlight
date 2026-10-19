package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/** kotlinx.serialization für JSON-Antworten (CLAUDE.md, Technik). Nur die JSON-Bibliothek, kein Netzwerkzugriff. */
class SerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        target.dependencies.add("implementation", target.catalog.library("kotlinx-serialization-json"))
    }
}
