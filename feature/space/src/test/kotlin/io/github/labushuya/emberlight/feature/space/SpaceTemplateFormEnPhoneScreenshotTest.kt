package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden: Formular „Use as template for new space“ mit Hinweisen, englisch (Paket 3.6c). Ein Golden je Klasse (ADR 0006). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceTemplateFormEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    @Test
    fun templateFormPhoneEnglish() {
        capture({
            SpaceEditContent(
                state = RetentionFixtures.templateState(),
                onChange = {},
                onLifetime = {},
                onPickCover = {},
                onRemoveCover = {},
                onSave = {},
                onClose = {},
                onConfirmDiscard = {},
                onDismissDiscard = {},
            )
        }, "src/test/screenshots/SpaceTemplateForm_phone_en.png")
    }
}
