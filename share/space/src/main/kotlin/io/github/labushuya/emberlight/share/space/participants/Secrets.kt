package io.github.labushuya.emberlight.share.space.participants

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Geheimnisse des Beitritts (ADR 0018). Das Einladungsgeheimnis und der persönliche Token sind je 256 bit Zufall,
 * base64url ohne Padding. Gespeichert wird nur der SHA-256 als Hex in Kleinbuchstaben. Der Klartext erscheint nur in
 * der Antwort bzw. im QR-Code.
 */
object Secrets {
    private const val BYTES = 32
    private const val LENGTH = 43
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val pattern = Regex("[A-Za-z0-9_-]{$LENGTH}")

    /** Neues Geheimnis mit 256 bit. */
    fun generate(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /** Form prüfen, bevor etwas verglichen wird. Falsche Form ist ein Fehlversuch, kein Fehler. */
    fun isWellFormed(value: String): Boolean = pattern.matches(value)

    /** SHA-256 des UTF-8-Textes als Hex in Kleinbuchstaben. Derselbe Wert gilt für Einladung und Token. */
    fun hashHex(secret: String): String =
        MessageDigest
            .getInstance(SHA_256)
            .digest(secret.toByteArray(Charsets.UTF_8))
            .toHex()

    /** Vergleich in konstanter Zeit zweier Hex-Hashes. */
    fun hashesEqual(
        leftHex: String,
        rightHex: String,
    ): Boolean = MessageDigest.isEqual(leftHex.toByteArray(Charsets.US_ASCII), rightHex.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private const val SHA_256 = "SHA-256"
}
