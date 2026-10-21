package io.github.labushuya.emberlight.feature.space

import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Bestätigung „Aus Liste entfernen“, deutsch (Paket 3.6c). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceRemoveDialogDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun removeDialogPhoneGerman() {
        capture({
            DialogBody(title = stringResource(R.string.space_list_remove_title)) {
                Text(stringResource(R.string.space_list_remove_text, "Sommerfest"))
            }
        }, "src/test/screenshots/SpaceRemoveDialog_phone_de.png")
    }
}
