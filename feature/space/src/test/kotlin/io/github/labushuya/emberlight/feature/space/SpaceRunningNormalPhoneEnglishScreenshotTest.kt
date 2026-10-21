package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden des laufenden Space auf dem Telefon, Englisch (ADR 0006). Eigene Klasse, weil die erste Aufnahme einer Klasse
 * gelegentlich leer bleibt. Im Verbund mit den anderen Aufnahmen war dieses Bild wiederholt leer (Testlauf, Verifikation).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRunningNormalPhoneEnglishScreenshotTest : SpaceRunScreenshotSupport() {
    private companion object {
        const val GIB_BYTES = 1024L * 1024L * 1024L
    }

    @Test
    fun runningNormalPhoneEnglish() {
        capture({
            Running(state(usedBytes = 1 * GIB_BYTES))
        }, "src/test/screenshots/SpaceRunning_phone_en.png")
    }
}
