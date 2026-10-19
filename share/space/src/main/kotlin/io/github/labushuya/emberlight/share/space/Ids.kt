package io.github.labushuya.emberlight.share.space

import java.security.SecureRandom
import java.util.Base64

/**
 * Zufällige Kennungen mit 128 bit, Base64url ohne Padding (22 Zeichen, ADR 0014). Sie sind nicht sequenziell und nicht
 * der Hash eines Inhalts. Der Client wählt sie nie.
 */
internal object OpaqueId {
    private const val BYTES = 16
    private const val LENGTH = 22
    private val random = SecureRandom()
    private val pattern = Regex("[A-Za-z0-9_-]{$LENGTH}")
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun isValid(value: String): Boolean = pattern.matches(value)
}

/** Kennung eines Space. Ein Server bedient genau einen Space (ADR 0014). */
@JvmInline
value class SpaceId(
    val value: String,
) {
    init {
        require(OpaqueId.isValid(value)) { "Ungültige Space-ID." }
    }

    companion object {
        fun generate(): SpaceId = SpaceId(OpaqueId.generate())

        fun parse(value: String): SpaceId? = if (OpaqueId.isValid(value)) SpaceId(value) else null
    }
}

/** Kennung eines Medium. Die Medien-ID ist kein Pfad und kein Hash (ADR 0013, IDs). */
@JvmInline
value class MediaId(
    val value: String,
) {
    init {
        require(OpaqueId.isValid(value)) { "Ungültige Medien-ID." }
    }

    companion object {
        fun generate(): MediaId = MediaId(OpaqueId.generate())

        fun parse(value: String): MediaId? = if (OpaqueId.isValid(value)) MediaId(value) else null
    }
}

/** Kennung eines Teilnehmers. Pro Space eindeutig. */
@JvmInline
value class ParticipantId(
    val value: String,
) {
    init {
        require(OpaqueId.isValid(value)) { "Ungültige Teilnehmer-ID." }
    }

    companion object {
        fun generate(): ParticipantId = ParticipantId(OpaqueId.generate())

        fun parse(value: String): ParticipantId? = if (OpaqueId.isValid(value)) ParticipantId(value) else null
    }
}
