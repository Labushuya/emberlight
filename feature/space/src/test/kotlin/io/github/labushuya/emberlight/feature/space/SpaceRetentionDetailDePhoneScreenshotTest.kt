package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Aufbewahrungsansicht eines beendeten Space, deutsch (Paket 3.6b): Löschdatum, Frist ändern, Jetzt löschen, Medienraster ohne Medien. Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceRetentionDetailDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionDetailPhoneGerman() {
        capture({
            SpaceDetailContent(record = RetentionFixtures.ended("Sommerfest", days = 7), coverFile = null, onEdit = {
            }, onDelete = {})
        }, "src/test/screenshots/SpaceRetentionDetail_phone_de.png")
    }
}
