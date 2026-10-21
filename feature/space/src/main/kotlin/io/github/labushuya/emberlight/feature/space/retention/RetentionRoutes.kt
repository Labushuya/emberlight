package io.github.labushuya.emberlight.feature.space.retention

/**
 * Ziele aus den Benachrichtigungen der Aufbewahrung (Paket 3.6b, ADR 0021). Sie laufen über das Ziel-Extra der Activity wie
 * die übrigen Hinweise. „Jetzt löschen“ öffnet den Space mit Bestätigung, es löscht nie direkt.
 */
object RetentionRoutes {
    private const val OPEN = "retention_open"
    private const val PURGE = "retention_purge"

    /** Ziel „Ansehen“: das Detail des Space. */
    fun open(spaceId: String): String = "$OPEN/$spaceId"

    /** Ziel „Jetzt löschen“: das Detail des Space mit geöffneter Bestätigung. */
    fun purge(spaceId: String): String = "$PURGE/$spaceId"

    /** Ein Ziel aus einer Benachrichtigung. [confirmPurge] öffnet die Bestätigung. */
    data class Target(
        val spaceId: String,
        val confirmPurge: Boolean,
    )

    /** Liest ein Ziel. Fremde oder unvollständige Ziele liefern `null` und werden ignoriert. */
    fun parse(route: String): Target? {
        val kind = route.substringBefore('/', missingDelimiterValue = "")
        val id = route.substringAfter('/', missingDelimiterValue = "")
        if (id.isEmpty()) return null
        return when (kind) {
            OPEN -> Target(id, confirmPurge = false)
            PURGE -> Target(id, confirmPurge = true)
            else -> null
        }
    }
}
