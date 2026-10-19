package io.github.labushuya.emberlight.share.space

private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB

/**
 * Grenzwerte eines Space (ADR 0013, Grenzen). Die Werte sind von der Nutzerin bzw. dem Nutzer am 18.10.2026 bestätigt.
 *
 * - [imageMaxBytes] und [videoMaxBytes]: Größe je Medienart, gilt beim Streamen, unabhängig von `Content-Length`.
 * - [storageLimitBytes]: Speicherlimit des Space. Ab [warningPercent] eine Warnung, darüber `507`.
 * - [minDeviceReserveBytes]: Freier Speicher des Geräts, der nach dem Schreiben bleiben muss.
 */
data class SpaceLimits(
    val imageMaxBytes: Long = 50 * MIB,
    val videoMaxBytes: Long = 2 * GIB,
    val storageLimitBytes: Long = 4 * GIB,
    val minDeviceReserveBytes: Long = 2 * GIB,
    val warningPercent: Int = 80,
) {
    init {
        require(imageMaxBytes > 0 && videoMaxBytes > 0) { "Dateigrenzen müssen positiv sein." }
        require(storageLimitBytes > 0) { "Das Speicherlimit muss positiv sein." }
        require(minDeviceReserveBytes >= 0) { "Der Mindest-Restspeicher darf nicht negativ sein." }
        require(warningPercent in 1..100) { "Die Warnschwelle liegt zwischen 1 und 100 Prozent." }
    }

    fun maxBytesFor(kind: MediaKind): Long =
        when (kind) {
            MediaKind.IMAGE -> imageMaxBytes
            MediaKind.VIDEO -> videoMaxBytes
        }

    /** Ab der Warnschwelle ist der Space voll genug für eine Warnung in der API-Antwort (ADR 0014, Upload). */
    fun isWarning(usedBytes: Long): Boolean = usedBytes * 100 >= storageLimitBytes * warningPercent
}
