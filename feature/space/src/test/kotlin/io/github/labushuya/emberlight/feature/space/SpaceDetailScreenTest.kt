package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Detail eines Space: Bearbeiten nur im Entwurf, Löschen nur nach Bestätigung (Paket 3.2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun deletingAsksForConfirmationAndNamesTheSpace() {
        var deleted = 0
        val record = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Hochzeit Lena")
        composeRule.setContent {
            EmberlightTheme {
                SpaceDetailContent(record = record, coverFile = null, onEdit = {}, onDelete = { deleted++ })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_detail_delete)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.space_delete_title)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.space_delete_text, "Hochzeit Lena"), substring = true)
            .assertIsDisplayed()
        assertEquals(0, deleted)

        composeRule.onNodeWithText(context.getString(R.string.space_delete_confirm)).performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun cancellingTheDialogDeletesNothing() {
        var deleted = 0
        composeRule.setContent {
            EmberlightTheme {
                SpaceDetailContent(
                    record = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Hochzeit"),
                    coverFile = null,
                    onEdit = {},
                    onDelete = { deleted++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_detail_delete)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.space_cancel)).performClick()

        assertEquals(0, deleted)
    }

    @Test
    fun editIsOnlyEnabledForDrafts() {
        composeRule.setContent {
            EmberlightTheme {
                SpaceDetailContent(
                    record = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Aktiv", status = SpaceStatus.ACTIVE),
                    coverFile = null,
                    onEdit = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_detail_edit)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.space_detail_not_editable)).assertIsDisplayed()
    }
}
