package io.github.labushuya.emberlight.feature.space.retention

/**
 * Ziele aus den Benachrichtigungen der Aufbewahrung (Paket 3.6b, ADR 0021; Paket 3.7, ADR 0022). Sie laufen über das
 * Ziel-Extra der Activity wie die übrigen Hinweise. „Jetzt löschen“ öffnet den Space mit Bestätigung, es löscht nie direkt.
 * „Ansehen“ öffnet die Auswahl zur Übernahme in die Galerie.
 */
object RetentionRoutes {
    private const val OPEN = "retention_open"
    private const val PURGE = "retention_purge"
    private const val EXPORT = "retention_export"

    /** Ziel „Ansehen“ der Erinnerung in der Benachrichtigung (Tippen auf die Meldung): das Detail des Space. */
    fun open(spaceId: String): String = "$OPEN/$spaceId"

    /** Ziel „Jetzt löschen“: das Detail des Space mit geöffneter Bestätigung. */
    fun purge(spaceId: String): String = "$PURGE/$spaceId"

    /** Ziel „Ansehen“ als Aktion: die Auswahl zur Übernahme in die Galerie (Paket 3.7). */
    fun export(spaceId: String): String = "$EXPORT/$spaceId"

    /**
     * Ein Ziel aus einer Benachrichtigung. [confirmPurge] öffnet die Bestätigung, [openExport] die Auswahl zur
     * Übernahme.
     */
    data class Target(
        val spaceId: String,
        val confirmPurge: Boolean,
        val openExport: Boolean = false,
    )

    /** Liest ein Ziel. Fremde oder unvollständige Ziele liefern `null` und werden ignoriert. */
    fun parse(route: String): Target? {
        val kind = route.substringBefore('/', missingDelimiterValue = "")
        val id = route.substringAfter('/', missingDelimiterValue = "")
        if (id.isEmpty()) return null
        return when (kind) {
            OPEN -> Target(id, confirmPurge = false)
            PURGE -> Target(id, confirmPurge = true)
            EXPORT -> Target(id, confirmPurge = false, openExport = true)
            else -> null
        }
    }
}
