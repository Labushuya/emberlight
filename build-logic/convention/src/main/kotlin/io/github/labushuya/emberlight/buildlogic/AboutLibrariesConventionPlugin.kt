package io.github.labushuya.emberlight.buildlogic

import com.mikepenz.aboutlibraries.plugin.AboutLibrariesExtension
import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.util.regex.Pattern

/**
 * Lizenzliste für „Über & Lizenzen“ (ADR 0020). Das Plugin erzeugt aus den Laufzeitabhängigkeiten eine Ressource
 * `aboutlibraries.json` für die App. Es steht im Build-Logic-Classpath, damit es denselben Classloader wie AGP hat.
 * Im Root-Projekt angewendet bricht es mit fehlenden AGP-Klassen ab.
 */
class AboutLibrariesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.mikepenz.aboutlibraries.plugin.android")
        target.extensions.configure<AboutLibrariesExtension> {
            // Nur die Release-Laufzeit. Das Plugin sammelt pro Variante, deshalb bekäme Debug sonst die Werkzeug-Abhängigkeiten.
            collect { it.filterVariants.addAll("release") }
            library {
                // Debug-only Werkzeuge (Compose-Tooling, Test-Manifest). Sie stehen in keinem Release-Build, nur in Debug.
                // Ohne Ausschluss stünden sie in der Debug-Liste. Der Unit-Test „releaseListHasNoTestOrToolingDependencies“ wacht darüber.
                // Vollständig verankert: ui-tooling-preview bleibt drin, es ist eine Laufzeitabhängigkeit.
                it.exclusionPatterns.addAll(
                    listOf(
                        "androidx.compose.ui:ui-tooling",
                        "androidx.compose.ui:ui-tooling-android",
                        "androidx.compose.ui:ui-tooling-data",
                        "androidx.compose.ui:ui-tooling-data-android",
                        "androidx.compose.ui:ui-test-manifest",
                    ).map { Pattern.compile("^${Pattern.quote(it)}$") },
                )
            }
            // Unbekannte oder nicht erlaubte Lizenzen erscheinen als Warnung im Build, statt still zu verschwinden.
            license {
                it.strictMode.set(StrictMode.WARN)
                it.allowedLicenses.addAll("Apache-2.0", "MIT", "BSD-3-Clause")
            }
        }
    }
}
