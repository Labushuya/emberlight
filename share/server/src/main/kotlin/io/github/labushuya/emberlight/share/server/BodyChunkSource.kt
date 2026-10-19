package io.github.labushuya.emberlight.share.server

import io.github.labushuya.emberlight.share.space.ChunkSource
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Der Körper endete vor der angekündigten `Content-Length`. Die Teildatei wird verworfen (ADR 0013). */
class IncompleteBodyException : IOException("Verbindung vor dem Ende des Körpers beendet.")

/**
 * Liest den Körper eines Uploads über eine eigene Schleife (ADR 0013). Jede Lesung wartet höchstens [pollMillis]. Danach
 * prüft [TransferGuard] Leerlauf, Mindestrate und Gesamtdauer mit der injizierten Uhr. Ein Verstoß wirft
 * [TransferAbortedException].
 *
 * Endet die Verbindung vor der [expectedLength], wirft die Klasse [IncompleteBodyException]. So entsteht aus einem
 * abgebrochenen Upload nie eine gespeicherte Datei. Die Grenze in Bytes setzt das Space-Modul beim Zählen.
 */
internal class BodyChunkSource(
    private val channel: ByteReadChannel,
    private val clock: MonotonicClock,
    policy: TransferPolicy,
    private val pollMillis: Long,
    private val expectedLength: Long?,
) : ChunkSource {
    private val guard = TransferGuard(policy, clock.nanos())
    private var received = 0L

    override suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        while (true) {
            val count = withTimeoutOrNull(pollMillis) { channel.readAvailable(buffer, offset, length) }
            val now = clock.nanos()
            when {
                count == null -> {
                    guard.check(now)?.let { throw TransferAbortedException(it) }
                }

                count < 0 -> {
                    if (expectedLength != null && received < expectedLength) throw IncompleteBodyException()
                    return -1
                }

                count > 0 -> {
                    received += count
                    guard.onBytes(count, now)
                    guard.check(now)?.let { throw TransferAbortedException(it) }
                    return count
                }

                // Null Bytes ohne Ende: wie kein Ereignis weiter warten, die Prüfung läuft beim nächsten Versuch.
                else -> {
                    guard.check(now)?.let { throw TransferAbortedException(it) }
                }
            }
        }
    }
}
