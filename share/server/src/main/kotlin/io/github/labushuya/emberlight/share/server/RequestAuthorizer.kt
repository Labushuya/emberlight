package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.Principal

/**
 * Erweiterungspunkt für den Zugriffsschutz (ADR 0012, ADR 0013). Die Prüfung läuft nach Host und Netz, vor jeder
 * Route, auch vor `/health` und unbekannten Pfaden.
 *
 * Liefert den [Principal] des Teilnehmers oder `null`. Der Standard [DenyAll] lehnt jede Anfrage ab.
 */
fun interface RequestAuthorizer {
    /** [authorizationHeader] ist der einzige Wert von `Authorization`, `null` wenn er fehlt. */
    fun authorize(authorizationHeader: String?): Principal?

    companion object {
        /** Verweigert jede Anfrage. Sicherer Standard, bis eine echte Prüfung eingesetzt wird. */
        val DenyAll: RequestAuthorizer = RequestAuthorizer { null }
    }
}
