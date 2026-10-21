package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Bestätigung „Jetzt löschen“, englisch (Paket 3.6b). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRetentionPurgeEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionPurgePhoneEnglish() {
        capture({
            DialogBody(title = "Delete now?") {
                RetentionPurgeText("Summer party")
            }
        }, "src/test/screenshots/SpaceRetentionPurge_phone_en.png")
    }
}
