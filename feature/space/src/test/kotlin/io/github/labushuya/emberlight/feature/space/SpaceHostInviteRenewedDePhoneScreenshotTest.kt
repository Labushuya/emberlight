package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ein Golden je Klasse (Paket 3.4b, ADR 0006). Der Hinweis zur automatisch erneuerten Einladung mit dem Knopf „Verstanden“.
 * Im gemeinsamen Prozess blieb eine spätere Aufnahme leer. Deshalb läuft jede Aufnahme in ihrer eigenen Klasse.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceHostInviteRenewedDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun inviteRenewedPhoneGerman() {
        capture(
            { Running(hostState(inviteRenewed = true)) },
            "src/test/screenshots/SpaceHostInviteRenewed_phone_de.png",
        )
    }
}
