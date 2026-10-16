package io.github.labushuya.emberlight.feature.gallery.timeline

import android.content.Context
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail

/**
 * Vorschaubilder für Compose-Tests und Goldens: jede Zelle bekommt eine feste, warme Farbe aus der Palette,
 * statt eines echten Bildes. So sind die Bilder deterministisch (coil-test, FakeImageLoaderEngine).
 */
@OptIn(DelicateCoilApi::class)
internal object TestThumbnails {
    private val palette =
        listOf(
            0xFF8A4B2A.toInt(),
            0xFFB5652F.toInt(),
            0xFFD9893F.toInt(),
            0xFF6E3B24.toInt(),
            0xFFC97A36.toInt(),
            0xFF4A2A1C.toInt(),
            0xFFA65A2A.toInt(),
            0xFFE0A15A.toInt(),
        )

    /** Setzt den Singleton-Loader für alle Bilder dieses Tests. Nach dem Test mit [reset] zurücksetzen. */
    fun install(items: List<MediaItem>) {
        SingletonImageLoader.setSafe { context -> loader(context, items) }
    }

    fun reset() {
        SingletonImageLoader.reset()
    }

    private fun loader(
        context: Context,
        items: List<MediaItem>,
    ): ImageLoader {
        val engine =
            FakeImageLoaderEngine
                .Builder()
                .apply {
                    items.forEachIndexed { index, item ->
                        intercept(
                            MediaThumbnail(uri = item.uri, type = item.type),
                            ColorImage(
                                palette[
                                    index %
                                        palette.size,
                                ],
                            ),
                        )
                    }
                }.build()
        return ImageLoader
            .Builder(context)
            .components { add(engine) }
            .build()
    }
}
