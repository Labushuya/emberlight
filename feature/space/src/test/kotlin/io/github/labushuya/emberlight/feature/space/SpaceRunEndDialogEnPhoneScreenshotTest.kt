package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.EndDialogText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden des Beenden-Dialogs mit dem Hinweis für Gäste (Paket 3.5a-3, ADR 0017). Ein Golden je Klasse (siehe
 * [SpaceRunScreenshotSupport]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRunEndDialogEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun endDialogPhoneEn() {
        capture({
            DialogBody(title = "End the space?") {
                EndDialogText()
            }
        }, "src/test/screenshots/SpaceRunEndDialog_phone_en.png")
    }
}
