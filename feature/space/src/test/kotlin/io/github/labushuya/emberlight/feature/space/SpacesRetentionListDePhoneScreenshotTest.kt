package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Liste mit einem beendeten Space und seinem Löschdatum, deutsch (Paket 3.6b). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpacesRetentionListDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionListPhoneGerman() {
        capture({
            SpacesListContent(RetentionFixtures.list(), null, {
                null
            }, {}, {})
        }, "src/test/screenshots/SpacesRetentionList_phone_de.png")
    }
}
