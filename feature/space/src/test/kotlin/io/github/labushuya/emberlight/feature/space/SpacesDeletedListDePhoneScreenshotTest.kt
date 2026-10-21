package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Liste mit einem gelöschten Space („Gelöscht am“) neben einem Entwurf, deutsch (Paket 3.6c). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpacesDeletedListDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun deletedListPhoneGerman() {
        capture({
            SpacesListContent(
                spaces = RetentionFixtures.deletedList(),
                selectedId = null,
                coverFile = { null },
                onSelect = {},
                onCreate = {},
            )
        }, "src/test/screenshots/SpacesDeletedList_phone_de.png")
    }
}
