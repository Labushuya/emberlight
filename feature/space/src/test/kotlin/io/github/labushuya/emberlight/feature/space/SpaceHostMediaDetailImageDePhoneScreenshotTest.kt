package io.github.labushuya.emberlight.feature.space

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.media.DetailMedia
import io.github.labushuya.emberlight.feature.space.run.HostMediaDetailBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Golden der Detailansicht mit einem echten Bild (Paket 3.5c, ADR 0006). Das Bild ist ein PNG, das der Test selbst schreibt.
 * Die Aufnahme zeigt das Bild mit Zoom-Ansicht und die Angaben darunter.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceHostMediaDetailImageDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun mediaDetailImagePhoneGerman() {
        val file = File.createTempFile("detail-bild", ".png")
        file.outputStream().use { out ->
            Bitmap
                .createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(IMAGE_COLOR) }
                .compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        }
        capture({
            HostMediaDetailBody(
                item = mediaState().items.first(),
                detail = DetailMedia(file, isVideo = false),
                loaded = true,
                editable = true,
                onRemoveRequest = {},
                onClose = {},
                modifier = Modifier.fillMaxSize(),
            )
        }, "src/test/screenshots/SpaceHostMediaDetailImage_phone_de.png")
    }

    private companion object {
        const val IMAGE_WIDTH = 96
        const val IMAGE_HEIGHT = 64
        const val IMAGE_COLOR = 0xFF8A5A3C.toInt()
        const val PNG_QUALITY = 100
    }
}
