package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Bestätigung „Jetzt löschen“, deutsch (Paket 3.6b, Designentscheidung (a)). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceRetentionPurgeDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionPurgePhoneGerman() {
        capture({
            DialogBody(title = "Jetzt löschen?") {
                RetentionPurgeText("Sommerfest")
            }
        }, "src/test/screenshots/SpaceRetentionPurge_phone_de.png")
    }
}
