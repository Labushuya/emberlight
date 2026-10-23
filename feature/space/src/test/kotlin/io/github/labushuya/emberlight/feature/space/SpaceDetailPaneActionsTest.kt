package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.management.SpaceStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Die Aktions-Knöpfe des Detail (Space starten, Einrichtung, Laufender Space) müssen in jeder Fensterbreite auslösen.
 * Regression: Im breiten Layout (ab 600 dp, etwa das Innendisplay eines Faltgeräts) fehlten die Rückrufe, die Knöpfe
 * blieben stumm. Die Tests tippen per Touch-Eingabe, damit eine darüberliegende Fläche den Treffer abfangen würde.
 */
@RunWith(AndroidJUnit4::class)
class SpaceDetailPaneActionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val started = mutableListOf<String>()
    private val setUp = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun render(record: SpaceRecord) {
        composeRule.setContent {
            EmberlightTheme {
                SpaceDetailPane(
                    state = SpaceDetailUiState(selected = record.id, loading = false, record = record),
                    coverFile = { null },
                    onEdit = {},
                    onDelete = {},
                    onStart = { started += it },
                    onSetup = { setUp += it },
                    onOpenRunning = { opened += it },
                )
            }
        }
    }

    private fun checkDraftButtons() {
        val record = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Hochzeit Lena")
        render(record)

        composeRule
            .onNodeWithText(context.getString(R.string.space_detail_start))
            .performScrollTo()
            .performTouchInput { click() }
        assertEquals(listOf(SpaceFixtures.ID_HOCHZEIT), started)

        composeRule
            .onNodeWithText(context.getString(R.string.space_detail_setup))
            .performScrollTo()
            .performTouchInput { click() }
        assertEquals(listOf(SpaceFixtures.ID_HOCHZEIT), setUp)
        assertEquals(1, started.size)
    }

    private fun checkActiveButton() {
        val record = SpaceFixtures.draft(SpaceFixtures.ID_HOCHZEIT, "Aktiv", status = SpaceStatus.ACTIVE)
        render(record)

        composeRule
            .onNodeWithText(context.getString(R.string.space_detail_open_running))
            .performScrollTo()
            .performTouchInput { click() }
        assertEquals(listOf(SpaceFixtures.ID_HOCHZEIT), opened)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun phoneDraftButtonsAct() = checkDraftButtons()

    @Test
    @Config(qualifiers = "en-w673dp-h728dp")
    fun innerFoldDisplayDraftButtonsAct() = checkDraftButtons()

    @Test
    @Config(qualifiers = "en-w840dp-h900dp")
    fun tabletDraftButtonsAct() = checkDraftButtons()

    @Test
    @Config(qualifiers = "en-w891dp-h411dp")
    fun landscapeDraftButtonsAct() = checkDraftButtons()

    @Test
    @Config(qualifiers = "en-w673dp-h728dp")
    fun innerFoldDisplayActiveButtonOpensRunningSpace() = checkActiveButton()

    @Test
    @Config(qualifiers = "en-w411dp-h891dp")
    fun phoneActiveButtonOpensRunningSpace() = checkActiveButton()
}
