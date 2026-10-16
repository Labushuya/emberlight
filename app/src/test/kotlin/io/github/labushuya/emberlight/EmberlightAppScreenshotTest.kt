package io.github.labushuya.emberlight

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots der gesamten App. Goldens liegen in `src/test/screenshots` (siehe ADR 0006).
 *
 * Die Größen sind Robolectric-Qualifier: Die Fensterklasse kommt aus der Displaygröße, damit Navigationsleiste
 * (Telefon) und Rail (Tablet quer) so erscheinen wie auf einem echten Gerät.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmberlightAppScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun phoneEnglish() {
        captureApp("src/test/screenshots/EmberlightApp_phone_en.png")
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun phoneGerman() {
        captureApp("src/test/screenshots/EmberlightApp_phone_de.png")
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun tabletLandscapeEnglish() {
        captureApp("src/test/screenshots/EmberlightApp_tablet_landscape_en.png")
    }

    private fun captureApp(path: String) {
        composeRule.setContent {
            EmberlightTheme {
                EmberlightApp()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }
}
