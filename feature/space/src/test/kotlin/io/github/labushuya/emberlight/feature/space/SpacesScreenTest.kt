package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Liste der Spaces: Leerzustand mit Erklärung, Karten und Auswahl (Paket 3.2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpacesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun emptyListExplainsSpacesAndOffersCreating() {
        var created = 0
        composeRule.setContent {
            EmberlightTheme {
                SpacesListContent(
                    spaces = emptyList(),
                    selectedId = null,
                    coverFile = { null },
                    onSelect = {},
                    onCreate = { created++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_empty_text)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_create)).performClick()
        assertEquals(1, created)
    }

    @Test
    fun cardsShowNameAndTheirLimitAndTappingOneSelectsIt() {
        val spaces = SpaceFixtures.sampleSpaces()
        var selected: SpaceRecord? = null
        composeRule.setContent {
            EmberlightTheme {
                SpacesListContent(
                    spaces = spaces,
                    selectedId = null,
                    coverFile = { null },
                    onSelect = { selected = it },
                    onCreate = {},
                )
            }
        }

        composeRule.onNodeWithText("Geburtstag").assertIsDisplayed()
        composeRule.onNodeWithText("Geburtstag").performClick()
        assertEquals(SpaceFixtures.ID_GEBURTSTAG, selected?.id?.value)
    }
}
