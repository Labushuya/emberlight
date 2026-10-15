package io.github.labushuya.emberlight.buildlogic

import org.gradle.api.provider.ProviderFactory

/**
 * Signierung des Release-Builds über Umgebungsvariablen.
 *
 * Werte werden nur im Speicher gehalten. Meldungen nennen ausschließlich Variablen-NAMEN.
 */
object ReleaseSigning {
    const val KEYSTORE_FILE = "EMBERLIGHT_KEYSTORE_FILE"
    const val KEYSTORE_PASSWORD = "EMBERLIGHT_KEYSTORE_PASSWORD"
    const val KEY_ALIAS = "EMBERLIGHT_KEY_ALIAS"
    const val KEY_PASSWORD = "EMBERLIGHT_KEY_PASSWORD"
    const val REQUIRE_PROPERTY = "emberlight.requireReleaseSigning"

    val VARIABLES: List<String> = listOf(KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

    /** Liest alle gesetzten, nicht leeren Variablen. Fehlende Variablen fehlen in der Map. */
    fun read(providers: ProviderFactory): Map<String, String> =
        VARIABLES
            .mapNotNull { name ->
                providers
                    .environmentVariable(name)
                    .orNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { value -> name to value }
            }.toMap()

    /** Namen der Variablen, die nicht gesetzt sind. */
    fun missing(values: Map<String, String>): List<String> = VARIABLES.filterNot { it in values }
}
