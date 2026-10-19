package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.form.LifetimeOption
import io.github.labushuya.emberlight.feature.space.form.SpaceField
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.share.space.management.SettingsError
import io.github.labushuya.emberlight.share.space.management.SpaceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** Formular: Fehler erscheinen am berührten Feld, „Speichern“ bleibt bis zu gültigen Eingaben gesperrt (Paket 3.2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceEditScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val gib = 1024L * 1024L * 1024L

    private fun state(
        form: SpaceForm = SpaceForm(),
        errors: Set<SettingsError> = emptySet(),
        touched: Set<SpaceField> = emptySet(),
        showDiscard: Boolean = false,
        storageMax: Long = SpaceRules.STORAGE_MAX_BYTES,
        pendingName: String? = null,
    ) = SpaceEditUiState(
        loaded = true,
        isNew = true,
        form = form,
        initial = SpaceForm(),
        errors = errors,
        touched = touched,
        showDiscard = showDiscard,
        storageMaxBytes = storageMax,
        pendingName = pendingName,
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

    @Test
    fun storageChoicesAboveTheDeviceCeilingAreGreyedOutWithAHint() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(form = SpaceForm(name = "Hochzeit"), storageMax = 8 * gib),
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

        composeRule.onNodeWithText("16 GiB").assertIsNotEnabled()
        composeRule.onNodeWithText("8 GiB").assertIsEnabled()
        composeRule
            .onNodeWithText(
                context.getString(R.string.space_storage_not_enough, "8"),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.space_storage_available, "8"),
            ).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aDeviceWithoutRoomExplainsWhyNoSpaceCanBeCreated() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state =
                        state(
                            form = SpaceForm(name = "Hochzeit"),
                            storageMax = 0,
                            errors = setOf(SettingsError.STORAGE_ABOVE_MAX),
                        ),
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

        composeRule
            .onNodeWithText(
                context.getString(R.string.space_storage_none_possible),
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_save)).assertIsNotEnabled()
    }

    @Test
    fun theDiceButtonSuggestsANameAndTheRetentionIsExplained() {
        var suggested = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = {},
                    onClose = {},
                    onConfirmDiscard = {},
                    onDismissDiscard = {},
                    onSuggestName = { suggested++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.space_name_suggest)).performClick()
        composeRule
            .onNodeWithText(
                context.getString(R.string.space_retention_explainer),
            ).performScrollTo()
            .assertIsDisplayed()
        assertEquals(1, suggested)
    }

    @Test
    fun aSuggestionReplacingAnOwnNameAsksFirst() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state = state(form = SpaceForm(name = "Eigen"), pendingName = "Tanzende Igel"),
                    onChange = {},
                    onLifetime = {},
                    onPickCover = {},
                    onRemoveCover = {},
                    onSave = {},
                    onClose = {},
                    onConfirmDiscard = {},
                    onDismissDiscard = {},
                    onConfirmName = { confirmed++ },
                    onDismissName = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_name_replace_title)).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.space_name_replace_text, "Tanzende Igel"),
            ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_name_replace_dismiss)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.space_name_replace_confirm)).performClick()
        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    @Test
    fun aCustomEndShowsTheDateAndTimeChoices() {
        val date = LocalDate.of(2026, 10, 17)
        val time = LocalTime.of(22, 30)
        composeRule.setContent {
            EmberlightTheme {
                SpaceEditContent(
                    state =
                        state(
                            form =
                                SpaceForm(
                                    name = "Abend",
                                    lifetime = LifetimeOption.UNTIL,
                                    untilDate = date,
                                    untilTime = time,
                                ),
                        ),
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

        composeRule.onNodeWithText(formatLocalDate(date)).assertIsDisplayed()
        composeRule.onNodeWithText(formatLocalTime(time)).assertIsDisplayed()
    }
}
