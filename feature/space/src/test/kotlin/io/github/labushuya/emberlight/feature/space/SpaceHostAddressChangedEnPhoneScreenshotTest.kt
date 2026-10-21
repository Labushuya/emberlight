package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.ParticipantsSection
import io.github.labushuya.emberlight.feature.space.run.RemoveParticipantText
import io.github.labushuya.emberlight.feature.space.run.RotateInviteText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ein Golden je Klasse (Paket 3.4, ADR 0006). Im gemeinsamen Prozess blieb eine spätere Aufnahme leer. Deshalb läuft jede
 * Aufnahme in ihrer eigenen Klasse.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceHostAddressChangedEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun addressChangedPhoneEnglish() {
        capture(
            { Running(hostState(addressChanged = true)) },
            "src/test/screenshots/SpaceHostAddressChanged_phone_en.png",
        )
    }
}
