package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

/**
 * Lader für die Vorschau in Bildschirmgröße in Tests. Er liefert ein echtes Bitmap als `BitmapImage`, wie das System es liefert.
 * Jede Anfrage wird gezählt. Mit [gate] wartet jede Anfrage, bis der Test das Gate öffnet. So lässt sich die Ankunft der
 * Vorschau zeitlich festlegen, etwa in die Öffnen-Animation.
 *
 * Mit [synchronous] laufen die Anfragen auf dem Haupt-Dispatcher des Tests statt auf einem Hintergrund-Thread. Die Ankunft
 * hängt dann nicht an der realen Zeit, sondern an der Reihenfolge im Test-Scheduler. Der Hero-Test braucht das, siehe
 * `ViewerScreenPreviewComposeTest`.
 */
internal class ScreenPreviewFakes(
    private val synchronous: Boolean = false,
) {
    val requested = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null

    fun loader(context: Context): ImageLoader =
        ImageLoader
            .Builder(context)
            .apply { if (synchronous) interceptorCoroutineContext(Dispatchers.Main.immediate) }
            .components {
                add(
                    object : Fetcher.Factory<MediaThumbnail> {
                        override fun create(
                            data: MediaThumbnail,
                            options: Options,
                            imageLoader: ImageLoader,
                        ): Fetcher =
                            Fetcher {
                                requested += data.uri
                                gate?.await()
                                ImageFetchResult(
                                    image =
                                        Bitmap
                                            .createBitmap(
                                                BITMAP_EDGE,
                                                BITMAP_EDGE,
                                                Bitmap.Config.ARGB_8888,
                                            ).asImage(),
                                    isSampled = true,
                                    dataSource = DataSource.MEMORY,
                                )
                            }
                    },
                )
            }.build()

    private companion object {
        const val BITMAP_EDGE = 8
    }
}
