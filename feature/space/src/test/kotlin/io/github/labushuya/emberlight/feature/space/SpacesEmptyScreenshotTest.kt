package io.github.labushuya.emberlight.feature.space

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
 * Golden des leeren Listen-Zustands (Paket 3.2). Eigene Klasse, weil die Aufnahme im Lauf mit anderen Tests derselben
 * Klasse leer wird (ADR 0006, Befund). Allein gelaufen ist sie korrekt.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpacesEmptyScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun listEmptyPhoneEnglish() {
        composeRule.setContent {
            EmberlightTheme {
                SpacesListContent(emptyList(), null, { null }, {}, {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/SpacesListEmpty_phone_en.png")
    }
}
