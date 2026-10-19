package io.github.labushuya.emberlight.feature.space

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Auswahl von Datum und Uhrzeit für das feste Ende (Entscheidung 4) und die Anzeige des Titelbilds über Coil. Die
 * Auswahl bleibt bei Abbruch ohne Wirkung, bei „Fertig“ kommt der gewählte Wert zurück.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceUntilPickersTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun theDateButtonOpensThePickerAndDoneReturnsTheChosenDay() {
        val day = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
        var picked: LocalDate? = null
        composeRule.setContent {
            EmberlightTheme {
                UntilPickers(date = day, time = LocalTime.NOON, onDate = { picked = it }, onTime = {})
            }
        }

        composeRule.onNodeWithText(formatLocalDate(day)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.space_picker_done)).performClick()

        assertEquals(day, picked)
    }

    @Test
    fun theTimeButtonOpensThePickerAndDoneReturnsTheChosenTime() {
        val time = LocalTime.of(10, 15)
        var picked: LocalTime? = null
        composeRule.setContent {
            EmberlightTheme {
                UntilPickers(date = null, time = time, onDate = {}, onTime = { picked = it })
            }
        }

        composeRule.onNodeWithText(formatLocalTime(time)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.space_picker_done)).performClick()

        assertEquals(time, picked)
    }

    @Test
    fun cancellingTheTimePickerChangesNothing() {
        var picked: LocalTime? = null
        composeRule.setContent {
            EmberlightTheme {
                UntilPickers(date = null, time = LocalTime.NOON, onDate = {}, onTime = { picked = it })
            }
        }

        composeRule.onNodeWithText(formatLocalTime(LocalTime.NOON)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.space_cancel)).performClick()

        assertNull(picked)
    }

    @Test
    fun aMissingDateShowsThePlaceholderOnTheButton() {
        composeRule.setContent {
            EmberlightTheme {
                UntilPickers(date = null, time = null, onDate = {}, onTime = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.space_until_pick_date)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.space_until_pick_time)).assertIsDisplayed()
    }

    @Test
    fun theTitleImageNodeIsShownForAStoredFile() {
        val file = folder.newFile("cover.jpg").apply { writeBytes(CoverFixtures.jpeg(size = 120)) }
        val description = context.getString(R.string.space_section_cover)
        composeRule.setContent {
            EmberlightTheme {
                CoverImage(file = file, colorIndex = 2, contentDescription = description)
            }
        }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }
}
