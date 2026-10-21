package io.github.labushuya.emberlight.share.space.participants

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM mit einem Schlüssel im AndroidKeyStore (ADR 0018). Der Keystore gibt Geheimschlüssel nie heraus, der
 * Schlüssel ist also nicht exportierbar. Er verlangt keine Nutzer-Authentifizierung, damit der Gastgeber den QR-Code
 * ohne Sperrbildschirm wieder zeigen kann. Der Schlüssel gehört zur Installation: Nach einem Restore auf anderen Geräten
 * fehlt er, [open] wirft dann [InviteSecretUnreadableException].
 */
class AndroidKeyStoreInviteSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : InviteSecretCipher {
    /** Erzeugt den Schlüssel beim ersten Mal. Der Keystore wählt einen zufälligen IV. */
    override fun seal(plaintext: String): SealedInvite {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SealedInvite(ciphertext = ciphertext, iv = cipher.iv)
    }

    override fun open(sealed: SealedInvite): String {
        val key = existingKey() ?: throw InviteSecretUnreadableException()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8)
        } catch (failure: GeneralSecurityException) {
            throw InviteSecretUnreadableException(failure)
        } catch (failure: IllegalArgumentException) {
            throw InviteSecretUnreadableException(failure)
        }
    }

    private fun existingKey(): SecretKey? =
        try {
            keyStore().getKey(alias, null) as? SecretKey
        } catch (failure: GeneralSecurityException) {
            throw InviteSecretUnreadableException(failure)
        }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (failure: IOException) {
            throw GeneralSecurityException("AndroidKeyStore not available", failure)
        }

    companion object {
        /** Alias des Schlüssels im AndroidKeyStore. Eigener Name, damit er mit anderen Schlüsseln der App nicht kollidiert. */
        const val DEFAULT_ALIAS = "emberlight.space.invite.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
