package io.github.labushuya.emberlight.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Hängt `checkReleaseManifest` an `check`. Prüft das gemergte Manifest der Release-Variante auf den Debug-Einstieg
 * (ADR 0014). Der Debug-Einstieg liegt im Paket `io.github.labushuya.emberlight.debugserver` und nur im Source-Set
 * `debug`.
 */
class ReleaseManifestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.withPlugin("com.android.application") {
            val components = target.extensions.getByType<ApplicationAndroidComponentsExtension>()
            components.onVariants(components.selector().withBuildType("release")) { variant ->
                // AGP kann den Callback je Variante mehrfach auslösen. Der Task entsteht deshalb nur einmal.
                val taskName = "checkReleaseManifestDebugEntry"
                if (target.tasks.findByName(taskName) == null) {
                    val check =
                        target.tasks.register<CheckReleaseManifestTask>(taskName) {
                            group = "verification"
                            description = "Bricht ab, wenn das Release-Manifest den Debug-Einstieg enthält."
                            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                            forbiddenTokens.set(FORBIDDEN_TOKENS)
                        }
                    target.tasks.named("check") { it.dependsOn(check) }
                }
            }
        }
    }

    companion object {
        /** Kennzeichen des Debug-Einstiegs (Paket und Präfix der Aktionen). */
        val FORBIDDEN_TOKENS: List<String> =
            listOf("io.github.labushuya.emberlight.debugserver", "DEBUG_SERVER")
    }
}
