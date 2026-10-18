package io.github.labushuya.emberlight.share.server

/**
 * Erweiterungspunkt für den Zugriffsschutz (ADR 0012). Die Prüfung läuft vor jeder Route, auch vor `/health`.
 *
 * Die konkrete Prüfung (Token, Einladungen) kommt mit Paket 3.x. Ohne eigene Implementierung gilt [DenyAll]: Der Server
 * lehnt jede Anfrage ab, es gibt keinen offenen Standardzugang.
 */
fun interface RequestAuthorizer {
    /** [authorizationHeader] ist der rohe Wert von `Authorization`, `null` wenn er fehlt. */
    fun isAuthorized(authorizationHeader: String?): Boolean

    companion object {
        /** Verweigert jede Anfrage. Sicherer Standard, bis eine echte Prüfung eingesetzt wird. */
        val DenyAll: RequestAuthorizer = RequestAuthorizer { false }
    }
}
