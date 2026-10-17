package io.github.labushuya.emberlight.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.createBitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import io.github.labushuya.emberlight.extensions.CoilComponents
import io.github.labushuya.emberlight.feature.gallery.viewer.SCREEN_PREVIEW_EDGE_PX
import io.github.labushuya.emberlight.feature.gallery.viewer.ScreenPreviewExtra
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Registriert den Demo-Fetcher vor den Medien-Thumbnails. Nur synthetische URIs bedient er. */
@Singleton
internal class DemoThumbnailComponents
    @Inject
    constructor() : CoilComponents {
        override fun contribute(builder: ComponentRegistry.Builder) {
            builder.add(DemoThumbnailFactory())
        }
    }

internal class DemoThumbnailFactory : Fetcher.Factory<MediaThumbnail> {
    override fun create(
        data: MediaThumbnail,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        val index = SyntheticLibrary.indexOf(data.uri) ?: return null
        // Die Vorschau in Bildschirmgröße (Vollbild-Viewer) bekommt das Motiv in dieser Größe, sonst das Thumbnail.
        val longEdge =
            if (options.extras.get(ScreenPreviewExtra) ==
                true
            ) {
                SCREEN_PREVIEW_EDGE_PX
            } else {
                THUMBNAIL_LONG_EDGE
            }
        return DemoThumbnailFetcher(SyntheticLibrary.itemAt(index), longEdge)
    }
}

/** Vorschaubild eines synthetischen Eintrags in der Auflösung des Thumbnails. Die Pixel entstehen im Speicher. */
internal class DemoThumbnailFetcher(
    private val item: MediaItem,
    private val longEdge: Int = THUMBNAIL_LONG_EDGE,
) : Fetcher {
    override suspend fun fetch(): FetchResult? =
        ImageFetchResult(
            image = renderDemoBitmap(item.id, item.timestamp, item.width, item.height, longEdge).asImage(),
            isSampled = false,
            dataSource = DataSource.MEMORY,
        )
}

/**
 * Das Motiv eines synthetischen Eintrags: ein Farbverlauf je Eintrag, darauf Nummer und Datum in großer Schrift. Vorschaubild
 * und Vollbild zeigen dasselbe Motiv, nur in anderer Auflösung. Das Seitenverhältnis folgt den Maßen des Eintrags, die lange
 * Kante hat [longEdge] Pixel.
 */
internal fun renderDemoBitmap(
    id: Long,
    timestamp: Long,
    width: Int?,
    height: Int?,
    longEdge: Int,
): Bitmap {
    val (bitmapWidth, bitmapHeight) = bitmapSize(width, height, longEdge)
    val bitmap = createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val hue = ((id * GOLDEN_ANGLE) % 360.0).toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val background =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(hsv(hue, 0.55f, 0.85f), hsv((hue + 60f) % 360f, 0.75f, 0.35f)),
        )
    background.setBounds(0, 0, bitmapWidth, bitmapHeight)
    background.draw(canvas)

    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    val numberSize = minOf(bitmapWidth, bitmapHeight) / 3f
    paint.textSize = numberSize
    paint.typeface = Typeface.DEFAULT_BOLD
    canvas.drawText(id.toString(), bitmapWidth / 2f, bitmapHeight / 2f, paint)
    paint.textSize = numberSize / 3.2f
    paint.typeface = Typeface.DEFAULT
    val date = DATE.format(Instant.ofEpochMilli(timestamp).atZone(ZONE))
    canvas.drawText(date, bitmapWidth / 2f, bitmapHeight / 2f + numberSize * 0.6f, paint)
    return bitmap
}

private fun bitmapSize(
    width: Int?,
    height: Int?,
    longEdge: Int,
): Pair<Int, Int> {
    val w = width ?: longEdge
    val h = height ?: longEdge
    val scale = longEdge.toFloat() / maxOf(w, h)
    return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
}

private fun hsv(
    hue: Float,
    saturation: Float,
    value: Float,
): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

/** Lange Kante des Vorschaubilds, wie bisher. */
internal const val THUMBNAIL_LONG_EDGE = 320

/** Lange Kante des Vollbilds: groß genug zum Zoomen und Lesen der Nummer, klein genug für den Speicher (ein Bild etwa 5 MB). */
internal const val FULL_LONG_EDGE = 1280

private const val GOLDEN_ANGLE = 137.508
private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
