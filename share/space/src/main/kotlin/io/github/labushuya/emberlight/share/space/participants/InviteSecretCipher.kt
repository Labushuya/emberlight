package io.github.labushuya.emberlight.share.space.participants

/**
 * Verschlüsselte Ablage des Einladungsgeheimnisses im Speicher der App (ADR 0018). Die Abstraktion trennt den
 * AndroidKeyStore von der Domäne, damit Tests einen Fake einsetzen können (Robolectric unterstützt den Keystore nicht).
 */
interface InviteSecretCipher {
    /** Verschlüsselt das Geheimnis. Der Schlüssel verlässt den Keystore nie, heraus kommen nur Ciphertext und IV. */
    fun seal(plaintext: String): SealedInvite

    /**
     * Entschlüsselt. Wirft [InviteSecretUnreadableException], wenn der Schlüssel fehlt (etwa nach einem Restore auf einem
     * anderen Gerät), der Ciphertext verändert ist oder das Format nicht passt.
     */
    fun open(sealed: SealedInvite): String
}

/** Ciphertext samt Initialisierungsvektor. Beides steht in der Datenbank, der Schlüssel nie. */
class SealedInvite(
    val ciphertext: ByteArray,
    val iv: ByteArray,
)

/** Das gespeicherte Geheimnis ist ohne den Schlüssel nicht lesbar. Die Domäne erzeugt dann eine neue Einladung. */
class InviteSecretUnreadableException(
    cause: Throwable? = null,
) : Exception("Invite secret cannot be decrypted", cause)
