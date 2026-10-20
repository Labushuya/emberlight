package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ein Golden je Klasse (Paket 3.4b, ADR 0006). Der Hinweis zur automatisch erneuerten Einladung mit dem Knopf „Got it“.
 * Im gemeinsamen Prozess blieb eine spätere Aufnahme leer. Deshalb läuft jede Aufnahme in ihrer eigenen Klasse.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceHostInviteRenewedEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun inviteRenewedPhoneEnglish() {
        capture(
            { Running(hostState(inviteRenewed = true)) },
            "src/test/screenshots/SpaceHostInviteRenewed_phone_en.png",
        )
    }
}
