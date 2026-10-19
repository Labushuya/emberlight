package io.github.labushuya.emberlight.debugserver

/**
 * Prüfung der Token-Hashes aus dem Start-Broadcast (ADR 0013, docs/DEBUG-SERVER.md). Der PC bildet den SHA-256 über die
 * UTF-8-Bytes des Tokens und übergibt nur diesen Hash. Ein gültiger Hash sind genau 64 Zeichen `[0-9a-f]`. Die App
 * kennt den Klartext-Token nie. Die Hashwerte stehen in keiner Meldung, auch nicht in Fehlermeldungen.
 */
internal object DebugTokens {
    private val HASH = Regex("[0-9a-f]{64}")

    fun isValid(hash: String?): Boolean = hash != null && HASH.matches(hash)

    /**
     * Prüft die Hashes eines Starts. Ein fehlender Gast-Hash (`null`) ist erlaubt, ein leerer oder ungültiger dagegen
     * nicht. Liefert [TokenCheck.Rejected] mit einer Meldung ohne Hashwerte.
     */
    fun check(
        hostHash: String?,
        guestHash: String?,
    ): TokenCheck =
        when {
            !isValid(hostHash) -> {
                TokenCheck.Rejected("host_token_sha256 fehlt oder ist ungültig (genau 64 Zeichen, 0-9 und a-f).")
            }

            guestHash != null && !isValid(guestHash) -> {
                TokenCheck.Rejected("guest_token_sha256 ist ungültig (genau 64 Zeichen, 0-9 und a-f).")
            }

            guestHash != null && guestHash == hostHash -> {
                TokenCheck.Rejected("host_token_sha256 und guest_token_sha256 müssen verschieden sein.")
            }

            else -> {
                TokenCheck.Accepted(host = checkNotNull(hostHash), guest = guestHash)
            }
        }
}

/** Ergebnis von [DebugTokens.check]. Die Werte sind SHA-256-Hashes in Kleinbuchstaben, keine Klartext-Tokens. */
internal sealed interface TokenCheck {
    data class Accepted(
        val host: String,
        val guest: String?,
    ) : TokenCheck

    data class Rejected(
        val reason: String,
    ) : TokenCheck
}
