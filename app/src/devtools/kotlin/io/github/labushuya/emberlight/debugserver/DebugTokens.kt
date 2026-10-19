package io.github.labushuya.emberlight.debugserver

import java.security.MessageDigest

/**
 * Prüfung und Hashing der Test-Tokens aus dem Start-Broadcast (ADR 0013, docs/DEBUG-SERVER.md). Ein gültiger Token sind
 * genau 64 Zeichen `[0-9a-f]`, also 256 bit. Die Token-Werte stehen in keiner Meldung, auch nicht in Fehlermeldungen.
 */
internal object DebugTokens {
    private val TOKEN = Regex("[0-9a-f]{64}")

    fun isValid(token: String?): Boolean = token != null && TOKEN.matches(token)

    /**
     * Prüft die Tokens eines Starts. Ein fehlender Gast-Token (`null`) ist erlaubt, ein leerer oder ungültiger dagegen
     * nicht. Liefert [TokenCheck.Rejected] mit einer Meldung ohne Tokenwerte.
     */
    fun check(
        hostToken: String?,
        guestToken: String?,
    ): TokenCheck =
        when {
            !isValid(hostToken) -> {
                TokenCheck.Rejected("host_token fehlt oder ist ungültig (genau 64 Zeichen, 0-9 und a-f).")
            }

            guestToken != null && !isValid(guestToken) -> {
                TokenCheck.Rejected("guest_token ist ungültig (genau 64 Zeichen, 0-9 und a-f).")
            }

            guestToken != null && guestToken == hostToken -> {
                TokenCheck.Rejected("host_token und guest_token müssen verschieden sein.")
            }

            else -> {
                TokenCheck.Accepted(host = checkNotNull(hostToken), guest = guestToken)
            }
        }

    /** SHA-256 des Tokens als 64 Hex-Zeichen in Kleinbuchstaben, so wie [io.github.labushuya.emberlight.share.server.ParticipantSeed] es erwartet. */
    fun sha256Hex(token: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

/** Ergebnis von [DebugTokens.check]. */
internal sealed interface TokenCheck {
    data class Accepted(
        val host: String,
        val guest: String?,
    ) : TokenCheck

    data class Rejected(
        val reason: String,
    ) : TokenCheck
}
