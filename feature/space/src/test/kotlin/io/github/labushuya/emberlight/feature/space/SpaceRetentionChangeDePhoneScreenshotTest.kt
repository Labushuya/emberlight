package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Dialog „Frist ändern“, deutsch (Paket 3.6b): Auswahl wie im Formular, heute eine Woche. Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceRetentionChangeDePhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionChangePhoneGerman() {
        capture({
            DialogBody(title = "Aufbewahrung ändern") {
                RetentionChangeText(option = RetentionOption.ONE_WEEK, onOption = {
                }, custom = "", onCustom = {})
            }
        }, "src/test/screenshots/SpaceRetentionChange_phone_de.png")
    }
}
