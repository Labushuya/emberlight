package io.github.labushuya.emberlight.core.media.thumbnail

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.map.Mapper
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/** Kantenlänge in Pixeln, falls Coil keine Zielgröße nennt (etwa bei `Size.ORIGINAL`). */
internal const val FALLBACK_THUMBNAIL_EDGE_PX = 512

/**
 * Längere Kante der Zielgröße in Pixeln. Die Zelle ist quadratisch mit Zuschnitt, so reicht die größere Kante,
 * damit kein Bereich unscharf nachskaliert wird.
 */
internal fun thumbnailEdgePx(size: coil3.size.Size): Int {
    val width = (size.width as? Dimension.Pixels)?.px
    val height = (size.height as? Dimension.Pixels)?.px
    return listOfNotNull(width, height).maxOrNull()?.takeIf { it > 0 } ?: FALLBACK_THUMBNAIL_EDGE_PX
}

/**
 * Lädt Vorschaubilder ab API 29 über [ContentResolver.loadThumbnail] in der Zielgröße. Das System cached diese
 * Thumbnails selbst, deshalb legt Coil keine Kopie auf dem Datenträger an (ADR 0008).
 *
 * Bricht Coil den Auftrag ab, wird das Abbrechen an das [CancellationSignal] weitergereicht, damit das Laden im
 * System aufhört und nicht im Hintergrund weiterläuft.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaThumbnailFetcher(
    private val thumbnail: MediaThumbnail,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val signal = CancellationSignal()
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) signal.cancel() }
        try {
            val edge = thumbnailEdgePx(options.size)
            val bitmap =
                options.context.contentResolver.loadThumbnail(
                    thumbnail.uri.toUri(),
                    Size(edge, edge),
                    signal,
                )
            return ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        } finally {
            handle?.dispose()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class MediaThumbnailFetcherFactory : Fetcher.Factory<MediaThumbnail> {
    override fun create(
        data: MediaThumbnail,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher = MediaThumbnailFetcher(data, options)
}

/**
 * Vor API 29 gibt es kein `loadThumbnail`. Die Anfrage wird auf die Content-URI abgebildet, Coils Standard-Decoder
 * übernimmt dann. Videos laufen über den `VideoFrameDecoder` aus `coil-video`.
 */
internal class MediaThumbnailUriMapper : Mapper<MediaThumbnail, Uri> {
    override fun map(
        data: MediaThumbnail,
        options: Options,
    ): Uri = data.uri.toUri()
}

/**
 * Registriert [MediaThumbnail] in Coil. Ab API 29 mit eigenem Fetcher, davor über die Uri-Abbildung.
 * [sdkInt] ist ein Parameter, damit beide Pfade auf dem JVM testbar sind.
 */
@SuppressLint("NewApi") // Der Aufruf von loadThumbnail steht nur hinter dem Vergleich mit sdkInt.
fun coil3.ComponentRegistry.Builder.addMediaThumbnails(
    sdkInt: Int = Build.VERSION.SDK_INT,
): coil3.ComponentRegistry.Builder =
    apply {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            add(MediaThumbnailFetcherFactory())
        } else {
            add(MediaThumbnailUriMapper())
        }
    }
