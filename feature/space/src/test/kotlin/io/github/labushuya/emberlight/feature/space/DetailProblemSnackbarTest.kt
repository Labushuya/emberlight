package io.github.labushuya.emberlight.feature.space

import android.content.Context
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stille Fehler sind sichtbar (Härtung vor v0.3.0-rc.1): Ein abgelehnter Vorgang erscheint als Snackbar mit dem Text der
 * Meldung und wird danach quittiert.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-w412dp-h915dp")
class DetailProblemSnackbarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aRefusedRetentionChangeIsShownInGerman() {
        var problem by mutableStateOf<DetailProblem?>(null)
        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            EmberlightTheme {
                SnackbarHost(hostState = hostState)
            }
            DetailProblemEffect(problem = problem, onConsume = { problem = null }, hostState = hostState)
        }

        composeRule.runOnIdle { problem = DetailProblem.RETENTION_NOT_CHANGED }

        composeRule
            .onNodeWithText(context.getString(R.string.space_problem_retention_not_changed))
            .assertIsDisplayed()
    }

    @Test
    fun aRemoveThatIsRefusedNamesTheCondition() {
        var problem by mutableStateOf<DetailProblem?>(null)
        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            EmberlightTheme {
                SnackbarHost(hostState = hostState)
            }
            DetailProblemEffect(problem = problem, onConsume = { problem = null }, hostState = hostState)
        }

        composeRule.runOnIdle { problem = DetailProblem.REMOVE_NOT_DELETED }

        composeRule
            .onNodeWithText(context.getString(R.string.space_problem_remove_not_deleted))
            .assertIsDisplayed()
    }
}
