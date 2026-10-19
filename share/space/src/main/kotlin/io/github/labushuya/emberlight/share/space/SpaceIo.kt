package io.github.labushuya.emberlight.share.space

/**
 * Quelle für den Körper eines Uploads. Die Ktor-Schicht liefert die Bytes, dieses Modul kennt kein HTTP.
 *
 * [read] schreibt bis zu [length] Bytes ab [offset] in [buffer] und liefert ihre Anzahl. `-1` heißt Ende des Körpers.
 * Ein Abbruch (Timeout, Verbindungsende) wirft eine Ausnahme.
 */
interface ChunkSource {
    suspend fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/**
 * Freier Speicher des Geräts in Bytes. Abstrakt, damit der Mindest-Restspeicher ohne echtes Gerät geprüft werden
 * kann.
 */
fun interface StorageProbe {
    fun usableBytes(): Long
}
