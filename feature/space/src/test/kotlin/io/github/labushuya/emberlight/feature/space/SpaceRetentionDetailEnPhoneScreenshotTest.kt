package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Aufbewahrungsansicht eines beendeten Space, englisch (Paket 3.6b). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRetentionDetailEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionDetailPhoneEnglish() {
        capture({
            SpaceDetailContent(record = RetentionFixtures.ended("Summer party", days = 7), coverFile = null, onEdit = {
            }, onDelete = {})
        }, "src/test/screenshots/SpaceRetentionDetail_phone_en.png")
    }
}
