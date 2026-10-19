package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.Principal
import java.security.MessageDigest

/**
 * Zugriff per Bearer-Token (ADR 0013, Authentifizierung). Gespeichert wird nur der SHA-256-Hash des Tokens. Der Vergleich
 * läuft über [MessageDigest.isEqual] auf die Hashes. Der Token selbst ist 256 bit Zufall, daher reicht der Hash ohne
 * Salt.
 */
class TokenAuthorizer(
    entries: Map<ByteArray, Principal>,
) : RequestAuthorizer {
    private val byHash: List<Pair<ByteArray, Principal>> = entries.entries.map { it.key to it.value }

    override fun authorize(authorizationHeader: String?): Principal? {
        val token = authorizationHeader?.let(::bearerToken) ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        // Alle Einträge vergleichen, damit die Laufzeit nicht davon abhängt, welcher Eintrag passt.
        var found: Principal? = null
        for ((expected, principal) in byHash) {
            if (MessageDigest.isEqual(expected, hash)) found = principal
        }
        return found
    }

    private fun bearerToken(header: String): String? {
        if (!header.startsWith(PREFIX, ignoreCase = true)) return null
        val token = header.substring(PREFIX.length)
        return token.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_CHARS && it.none { c -> c.isWhitespace() } }
    }

    companion object {
        private const val PREFIX = "Bearer "
        private const val MAX_TOKEN_CHARS = 256
    }
}
