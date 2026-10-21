package io.github.labushuya.emberlight.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * Goldens des Bildschirms „Über & Lizenzen“ (ADR 0020, ADR 0006): Telefon und großes Display, Deutsch und Englisch.
 * Der Inhalt kommt aus [AboutFixtures], damit die Aufnahme nicht vom Build oder von der Liste abhängt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun phoneEnglish() {
        capture("src/test/screenshots/AboutScreen_phone_en.png")
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun phoneGerman() {
        capture("src/test/screenshots/AboutScreen_phone_de.png")
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun largeEnglish() {
        capture("src/test/screenshots/AboutScreen_large_en.png")
    }

    @Test
    @Config(qualifiers = "de-w900dp-h600dp")
    fun largeGerman() {
        capture("src/test/screenshots/AboutScreen_large_de.png")
    }

    private fun capture(path: String) {
        composeRule.setContent {
            EmberlightTheme {
                AboutScreen(onBack = {}, content = AboutFixtures.CONTENT)
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }
}
