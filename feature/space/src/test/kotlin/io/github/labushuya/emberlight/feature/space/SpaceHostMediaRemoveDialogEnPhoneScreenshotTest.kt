package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.MediaRemoveText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden der Medienansicht des Gastgebers (Paket 3.5c, ADR 0006). Eine Konfiguration je Klasse, weil Robolectric beim
 * Wechsel der Qualifier eine neue Umgebung anlegt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceHostMediaRemoveDialogEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun mediaRemoveDialogPhoneEnglish() {
        capture({
            DialogBody(title = "Remove this media?") { MediaRemoveText() }
        }, "src/test/screenshots/SpaceHostMediaRemoveDialog_phone_en.png")
    }
}
