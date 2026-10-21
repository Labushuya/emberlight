package io.github.labushuya.emberlight.share.space.preview

import kotlin.math.roundToInt

/**
 * Regeln der Vorschauen (Paket 3.5a-2, ADR 0019). Reine Funktionen ohne Android, damit sie ohne Gerät geprüft werden.
 */
object PreviewRules {
    /** Lange Kante der Vorschau in Pixeln, für Bilder und Videos. */
    const val PREVIEW_LONG_EDGE = 320

    /** Lange Kante der Ansicht, nur für HEIC und HEIF. */
    const val VIEW_LONG_EDGE = 1600

    /** JPEG-Qualität der Vorschauen. Das Bild wird neu kodiert, daher entsteht kein EXIF. */
    const val JPEG_QUALITY = 80

    /** Pixelgrenze (100 Megapixel). Darüber wird nicht dekodiert, das Ergebnis ist `failed` (ADR 0019). */
    const val MAX_PIXELS = 100_000_000L

    /** Höchstdauer einer Erzeugung. Danach `failed`, ohne neuen Versuch. */
    const val RENDER_TIMEOUT_MS = 30_000L

    /** Ab dieser Dauer wird der Frame bei 1 s genommen, darunter bei 10 % der Dauer. */
    private const val LONG_VIDEO_MS = 10_000L

    private const val LONG_SEEK_US = 1_000_000L

    private val HEIC_MIMES = setOf("image/heic", "image/heif")

    /** Ob das Medium eine 1600-px-Ansicht bekommt (nur HEIC und HEIF). */
    fun hasHeicView(mime: String): Boolean = mime in HEIC_MIMES

    fun isVideo(mime: String): Boolean = mime.startsWith("video/")

    /**
     * Zielmaße mit erhaltenem Seitenverhältnis, lange Kante auf [longEdge]. Kleinere Bilder werden nicht vergrößert.
     */
    fun targetSize(
        width: Int,
        height: Int,
        longEdge: Int,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Maße müssen positiv sein." }
        val long = maxOf(width, height)
        if (long <= longEdge) return width to height
        val scale = longEdge.toDouble() / long
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Größte Zweierpotenz für `inSampleSize`, bei der die lange Kante noch mindestens [longEdge] Pixel hat. Dekodiert wird
     * damit nur so grob wie nötig (Subsampling).
     */
    fun sampleSize(
        width: Int,
        height: Int,
        longEdge: Int,
    ): Int {
        val long = maxOf(width, height)
        var sample = 1
        while (long / (sample * 2) >= longEdge) sample *= 2
        return sample
    }

    /** `true`, wenn das Bild über der Pixelgrenze liegt. */
    fun exceedsPixelLimit(
        width: Int,
        height: Int,
    ): Boolean = width.toLong() * height.toLong() > MAX_PIXELS

    /**
     * Zeitpunkt des Vorschau-Frames in Mikrosekunden: 1 s bei langen Videos, bei kurzen 10 % der Dauer. Ohne bekannte
     * Dauer bleibt es beim ersten Frame.
     */
    fun seekMicros(durationMs: Long?): Long {
        if (durationMs == null || durationMs <= 0) return 0L
        return if (durationMs >= LONG_VIDEO_MS) LONG_SEEK_US else durationMs * 100L
    }

    /**
     * Name des Video-Codecs aus dem MIME-Typ der Spur, im Stil der Vierzeichen-Codes (`avc1`, `hvc1`). `null` für Codecs,
     * die die Liste nicht kennt. Dann gilt der Codec als nicht erkannt.
     */
    fun codecOf(mime: String): String? =
        when (mime) {
            "video/avc" -> "avc1"
            "video/hevc" -> "hvc1"
            "video/x-vnd.on2.vp8" -> "vp08"
            "video/x-vnd.on2.vp9" -> "vp09"
            "video/av01" -> "av01"
            "video/mp4v-es" -> "mp4v"
            "video/3gpp" -> "s263"
            else -> null
        }
}

/** Zustand einer Vorschau oder Ansicht im Index (ADR 0019). `null` im Index heißt: noch nicht eingereiht. */
object PreviewState {
    const val PENDING = "PENDING"
    const val READY = "READY"
    const val FAILED = "FAILED"

    /** Wert für die API: Kleinbuchstaben, `null` bleibt `null`. */
    fun wire(state: String?): String? = state?.lowercase()
}
