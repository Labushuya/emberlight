package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.HostVideoState
import io.github.labushuya.emberlight.feature.space.run.VideoDetailContent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden der Videosteuerung der Gastgeber-Detailansicht (Hotfix 2). Ohne Player: Die Fläche ist ein dunkles Feld, damit
 * die Knöpfe und der Fehlertext zu sehen sind.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceHostVideoControlsDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    private val surface: @androidx.compose.runtime.Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1B1410)))
    }

    @Test
    fun readyToPlayShowsThePlayButtonWithoutSurface() {
        capture({
            VideoDetailContent(HostVideoState(), surface, onPlay = {}, onPause = {})
        }, "src/test/screenshots/SpaceHostVideoControls_bereit_de.png")
    }

    @Test
    fun playingShowsThePauseButtonOverTheSurface() {
        capture({
            VideoDetailContent(HostVideoState(surface = true, playing = true), surface, onPlay = {}, onPause = {})
        }, "src/test/screenshots/SpaceHostVideoControls_spielt_de.png")
    }

    @Test
    fun failureShowsTheErrorText() {
        capture({
            VideoDetailContent(HostVideoState(failed = true), surface, onPlay = {}, onPause = {})
        }, "src/test/screenshots/SpaceHostVideoControls_fehler_de.png")
    }
}
