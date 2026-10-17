package io.github.labushuya.emberlight

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.labushuya.emberlight.testing.FakeMediaRepository
import io.github.labushuya.emberlight.testing.TestImageLoader
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.inject.Inject

/**
 * Screenshots der gesamten App mit der Zeitleiste auf festen Daten. Goldens liegen in `src/test/screenshots`
 * (siehe ADR 0006). Gestartet wird MainActivity, sie setzt ihren Inhalt selbst.
 *
 * Die Größen sind Robolectric-Qualifier: Die Fensterklasse kommt aus der Displaygröße, damit Navigationsleiste
 * (Telefon) und Rail (Tablet quer) so erscheinen wie auf einem echten Gerät.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class)
class EmberlightAppScreenshotTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var repository: FakeMediaRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        TestImageLoader.install()
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestImageLoader.reset()
    }

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
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }
}
