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

/** Screenshot des Spaces-Platzhalters auf einem Telefon im Hochformat (Englisch). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpacesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholderPhoneEnglish() {
        composeRule.setContent {
            EmberlightTheme {
                SpacesScreen()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/SpacesPlaceholder_phone_en.png")
    }
}
