package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.export.SpaceExportContent
import io.github.labushuya.emberlight.feature.space.export.SpaceExportFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Auswahl zur Übernahme auf dem Telefon, englisch (Paket 3.7). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceExportPhoneEnScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun exportPhoneEnglish() {
        capture({
            SpaceExportContent(
                state = SpaceExportFixtures.selected(),
                onBack = {},
                onAll = {},
                onNone = {},
                onToggle = {},
                onStart = {},
                onCancel = {},
            )
        }, "src/test/screenshots/SpaceExport_phone_en.png")
    }
}
