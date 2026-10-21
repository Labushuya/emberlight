package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Zweck einer signierten Medien-URL (ADR 0019). Der Zweck steht in der Signatur und ist an genau eine Route gebunden:
 * eine Signatur für `preview` gilt nicht für `stream`. [ttlSeconds] ist die Gültigkeit ab Ausgabe.
 */
enum class MediaPurpose(
    val wire: String,
    val ttlSeconds: Long,
) {
    PREVIEW("preview", 10 * 60),
    VIEW("view", 10 * 60),
    DOWNLOAD("download", 10 * 60),
    STREAM("stream", 30 * 60),
}

/** Ausgabe einer Signatur: Ablauf als Sekunden seit der Epoche (UTC) und die Signatur (base64url ohne Auffüllung). */
data class MediaGrant(
    val expiresAt: Long,
    val signature: String,
)

/** Ergebnis der Prüfung einer Signatur. Die Reihenfolge ist fest: erst die Signatur, dann der Ablauf (ADR 0019). */
enum class MediaVerdict {
    VALID,
    EXPIRED,
    BAD_SIGNATURE,
}

/**
 * Signiert kurzlebige Medien-URLs mit HMAC-SHA256 (ADR 0019). Der Schlüssel sind 256 bit Zufall, nur im Speicher je
 * Serverlauf. Ein Neustart macht alle URLs ungültig, das ist gewollt.
 *
 * Signiert wird `mediaId|zweck|participantId|exp`. IDs sind geprüfte Zeichenketten ohne `|` (vgl. [OpaqueId]), damit ist
 * die Nachricht eindeutig. Die Zeit kommt von außen, damit Tests eine Fake-Uhr nutzen können.
 */
class MediaUrlSigner internal constructor(
    private val key: ByteArray,
    private val nowSeconds: () -> Long,
) {
    init {
        require(key.size == KEY_BYTES) { "Der Schlüssel braucht $KEY_BYTES Bytes." }
    }

    /** Signatur für ein Medium, einen Zweck und einen Teilnehmer, gültig ab jetzt für die Dauer des Zwecks. */
    fun grant(
        mediaId: String,
        purpose: MediaPurpose,
        participantId: ParticipantId,
    ): MediaGrant {
        val expiresAt = nowSeconds() + purpose.ttlSeconds
        return MediaGrant(expiresAt, sign(message(mediaId, purpose, participantId.value, expiresAt)))
    }

    /** Prüft Signatur und Ablauf. Der Vergleich läuft über [MessageDigest.isEqual] (konstante Laufzeit). */
    fun verify(
        mediaId: String,
        purpose: MediaPurpose,
        participantId: String,
        expiresAt: Long,
        signature: String,
    ): MediaVerdict {
        val expected = sign(message(mediaId, purpose, participantId, expiresAt)).toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(
                expected,
                signature.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return MediaVerdict.BAD_SIGNATURE
        }
        return if (nowSeconds() < expiresAt) MediaVerdict.VALID else MediaVerdict.EXPIRED
    }

    private fun message(
        mediaId: String,
        purpose: MediaPurpose,
        participantId: String,
        expiresAt: Long,
    ): String = "$mediaId|${purpose.wire}|$participantId|$expiresAt"

    private fun sign(message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return BASE64.encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val KEY_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"
        private val BASE64 = Base64.getUrlEncoder().withoutPadding()

        /** Neuer Schlüssel aus dem sicheren Zufallsgenerator. Die Uhr ist die Systemzeit, sofern nicht ersetzt. */
        fun random(nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }): MediaUrlSigner {
            val key = ByteArray(KEY_BYTES)
            SecureRandom().nextBytes(key)
            return MediaUrlSigner(key, nowSeconds)
        }

        /** Test-Nähte: fester Schlüssel und Fake-Uhr. Produktion nutzt [random]. */
        internal fun forTest(
            key: ByteArray,
            nowSeconds: () -> Long,
        ): MediaUrlSigner = MediaUrlSigner(key.copyOf(), nowSeconds)
    }
}

/** Ergebnis der Zugriffsprüfung per signierter URL. */
sealed interface SignedMediaAccess {
    /** Gültige Signatur, Ablauf offen, Teilnehmer aktiv. */
    data class Granted(
        val principal: Principal,
    ) : SignedMediaAccess

    /** Keine Signatur-Parameter in der Anfrage. Dann greift die Header-Prüfung. */
    data object Absent : SignedMediaAccess

    /** Signatur vorhanden, aber nicht gültig. [code] steht im Fehlerformat (ADR 0014). */
    data class Denied(
        val code: String,
    ) : SignedMediaAccess
}

/**
 * Prüft Parameter einer signierten Medien-URL (ADR 0019): Signatur, Ablauf, dann ob der Teilnehmer noch aktiv ist.
 * Entfernen wirkt damit sofort. Ob das Medium existiert, prüft die Route (404).
 */
class SignedMediaAuthorizer internal constructor(
    private val signer: MediaUrlSigner,
    private val activePrincipal: (ParticipantId) -> Principal?,
) {
    /** [participant], [expires] und [signature] sind die Query-Werte, `null` wenn sie fehlen. */
    fun authorize(
        mediaId: String,
        purpose: MediaPurpose,
        participant: String?,
        expires: String?,
        signature: String?,
    ): SignedMediaAccess {
        if (participant == null && expires == null && signature == null) return SignedMediaAccess.Absent
        if (participant == null || expires == null || signature == null) return SignedMediaAccess.Denied(URL_INVALID)
        val expiresAt = expires.toLongOrNull() ?: return SignedMediaAccess.Denied(URL_INVALID)
        val pid = ParticipantId.parse(participant) ?: return SignedMediaAccess.Denied(URL_INVALID)
        return when (signer.verify(mediaId, purpose, pid.value, expiresAt, signature)) {
            MediaVerdict.BAD_SIGNATURE -> {
                SignedMediaAccess.Denied(URL_INVALID)
            }

            MediaVerdict.EXPIRED -> {
                SignedMediaAccess.Denied(URL_EXPIRED)
            }

            MediaVerdict.VALID -> {
                activePrincipal(pid)?.let { SignedMediaAccess.Granted(it) }
                    ?: SignedMediaAccess.Denied(PARTICIPANT_INACTIVE)
            }
        }
    }

    companion object {
        const val URL_INVALID = "url_invalid"
        const val URL_EXPIRED = "url_expired"
        const val PARTICIPANT_INACTIVE = "participant_inactive"
    }
}
