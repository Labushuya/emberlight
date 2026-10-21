package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.form.RetentionOption
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Dialog „Frist ändern“, englisch (Paket 3.6b): eigene Dauer. Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceRetentionChangeEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun retentionChangePhoneEnglish() {
        capture({
            DialogBody(title = "Change keeping period") {
                RetentionChangeText(option = RetentionOption.CUSTOM, onOption = {
                }, custom = "12", onCustom = {})
            }
        }, "src/test/screenshots/SpaceRetentionChange_phone_en.png")
    }
}
