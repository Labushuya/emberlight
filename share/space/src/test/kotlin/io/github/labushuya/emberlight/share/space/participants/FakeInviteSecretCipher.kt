package io.github.labushuya.emberlight.share.space.participants

/**
 * Fake für Tests ohne AndroidKeyStore (Robolectric kann ihn nicht). Die „Verschlüsselung“ ist nur eine Bytefolge, die
 * nicht wie der Klartext aussieht. [keyLost] simuliert einen verlorenen Schlüssel, etwa nach einem Restore.
 */
class FakeInviteSecretCipher : InviteSecretCipher {
    var keyLost: Boolean = false

    override fun seal(plaintext: String): SealedInvite =
        SealedInvite(
            ciphertext = plaintext.toByteArray(Charsets.UTF_8).map { (it.toInt() xor MASK).toByte() }.toByteArray(),
            iv = ByteArray(IV_BYTES) { it.toByte() },
        )

    override fun open(sealed: SealedInvite): String {
        if (keyLost) throw InviteSecretUnreadableException()
        val plain = sealed.ciphertext.map { (it.toInt() xor MASK).toByte() }.toByteArray()
        return String(plain, Charsets.UTF_8)
    }

    private companion object {
        const val MASK = 0x5A
        const val IV_BYTES = 12
    }
}
