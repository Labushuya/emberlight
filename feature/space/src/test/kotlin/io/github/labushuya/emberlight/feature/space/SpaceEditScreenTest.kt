package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.form.SpaceField
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.share.space.management.SettingsError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Formular: Fehler erscheinen am berührten Feld, „Speichern“ bleibt bis zu gültigen Eingaben gesperrt (Paket 3.2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun state(
        form: SpaceForm = SpaceForm(),
        errors: Set<SettingsError> = emptySet(),
        touched: Set<SpaceField> = emptySet(),
        showDiscard: Boolean = false,
    ) = SpaceEditUiState(
        loaded = true,
        isNew = true,
        form = form,
        initial = SpaceForm(),
        errors = errors,
        touched = touched,
        showDiscard = showDiscard,
    )

    @Test
    fun saveStaysDisabledWhileTheNameIsMissingAndTheErrorShowsOnceTouched() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(errors = setOf(SettingsError.NAME_EMPTY), touched = setOf(SpaceField.NAME)),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = {},
                    onClose = {},
                    onConfirmDiscard = {},
                    onDismissDiscard = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_save)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.space_error_name_empty)).assertIsDisplayed()
    }

    @Test
    fun untouchedFieldsDoNotShowTheirErrorYet() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(errors = setOf(SettingsError.NAME_EMPTY)),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = {},
                    onClose = {},
                    onConfirmDiscard = {},
                    onDismissDiscard = {},
                )
            }
        }

        assertTrue(
            composeRule
                .onAllNodesWithText(
                    context.getString(R.string.space_error_name_empty),
                ).fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun validFormCanBeSaved() {
        var saved = 0
        var discarded = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(form = SpaceForm(name = "Hochzeit")),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = { saved++ },
                    onClose = {},
                    onConfirmDiscard = { discarded++ },
                    onDismissDiscard = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_save)).assertIsEnabled().performClick()
        assertEquals(1, saved)
    }

    @Test
    fun discardDialogConfirmsTheDiscard() {
        var discarded = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(form = SpaceForm(name = "Hochzeit"), showDiscard = true),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = {},
                    onClose = {},
                    onConfirmDiscard = { discarded++ },
                    onDismissDiscard = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_discard_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_discard_confirm)).performClick()
        assertEquals(1, discarded)
    }
}
