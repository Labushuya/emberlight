package io.github.labushuya.emberlight.share.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Zugriffstoken für den lokalen Server. Der Vergleich läuft konstant in der Zeit, damit Antwortzeiten keine Zeichen
 * des Tokens verraten. Der Wert erscheint nie in `toString()` und in keiner Logausgabe.
 */
class AccessToken private constructor(
    private val bytes: ByteArray,
) {
    /** Prüft einen Kandidaten. `null` ist immer ungültig. */
    fun matches(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(bytes, candidate.toByteArray(Charsets.UTF_8))
    }

    override fun toString(): String = "AccessToken(***)"

    override fun equals(other: Any?): Boolean = other is AccessToken && MessageDigest.isEqual(bytes, other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        private const val MIN_LENGTH = 16
        private const val GENERATED_BYTES = 32

        /** Erzeugt 256 Bit aus dem sicheren Zufallsgenerator, Base64url ohne Auffüllung. */
        fun generate(random: SecureRandom = SecureRandom()): AccessToken {
            val raw = ByteArray(GENERATED_BYTES).also { random.nextBytes(it) }
            return AccessToken(
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(raw)
                    .toByteArray(Charsets.UTF_8),
            )
        }

        /** Übernimmt einen vorgegebenen Wert, etwa für Tests oder den Debug-Spike. Mindestens 16 Zeichen. */
        fun of(value: String): AccessToken {
            require(value.length >= MIN_LENGTH) { "Token muss mindestens $MIN_LENGTH Zeichen haben." }
            require(value.none { it.isWhitespace() }) { "Token darf keine Leerzeichen enthalten." }
            return AccessToken(value.toByteArray(Charsets.UTF_8))
        }
    }
}
