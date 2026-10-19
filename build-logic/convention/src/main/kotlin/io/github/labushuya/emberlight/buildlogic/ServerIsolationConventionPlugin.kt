package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Build-Sperre für `share:server` (ADR 0012, 0014). Zwei Prüfungen hängen in `check`:
 *
 * - `checkServerIsolation`: Der Server darf über keine auflösbare Konfiguration (Compile, Runtime, Unit-Test,
 *   AndroidTest, alle Varianten) ein verbotenes Modul erreichen. Verboten sind die Galerie (`:core:media`,
 *   `:feature:*`) und die App. `:share:space` ist erlaubt, der Server braucht es.
 * - `checkServerSources`: Der Quelltext des Servers (`src/`) enthält keine Medien- oder Speicherzugriffe der Galerie.
 *
 * Damit bleibt die Trennung aus [docs/KONZEPT.md] auch im Build erzwungen.
 */
class ServerIsolationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.registerGraphIsolationCheck("checkServerIsolation", FORBIDDEN_PROJECT_PATHS)
        target.registerSourceIsolationCheck("checkServerSources", FORBIDDEN_SOURCE_TOKENS)
    }

    companion object {
        /** Projekte, die share:server nie erreichen darf. `:feature:*` sind die Oberflächen-Module. */
        val FORBIDDEN_PROJECT_PATHS: List<String> = listOf(":app", ":core:media", ":feature:*")

        /** Tokens, die im Quelltext des Servers nicht vorkommen dürfen (Galerie- und Speicherzugriffe). */
        val FORBIDDEN_SOURCE_TOKENS: List<String> = GALLERY_ACCESS_TOKENS
    }
}
