package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Touch-Tests der Auswahl auf breiten Displays und dem aufgeklappten Faltdisplay (Paket 3.7). Die Knöpfe werden mit
 * echter Touch-Injection angeklickt, nicht nur über Semantik. Jede Konfiguration hat eine eigene Testklasse.
 */
abstract class SpaceExportTouchSupport {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()

    private fun show(state: SpaceExportUiState) {
        calls.clear()
        composeRule.setContent {
            EmberlightTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpaceExportContent(
                        state = state,
                        onBack = {},
                        onAll = { calls += "all" },
                        onNone = { calls += "none" },
                        onToggle = {},
                        onStart = { calls += "start" },
                        onCancel = { calls += "cancel" },
                    )
                }
            }
        }
    }

    @Test
    fun allNoneAndStartRespondToTouch() {
        show(SpaceExportFixtures.selected())
        composeRule.onNodeWithText(context.getString(R.string.space_export_all)).performTouchInput { click() }
        composeRule.onNodeWithText(context.getString(R.string.space_export_none)).performTouchInput { click() }
        composeRule.onNodeWithText(context.getString(R.string.space_export_start)).performTouchInput { click() }
        assertEquals(listOf("all", "none", "start"), calls)
    }

    @Test
    fun startStaysReachableWithManyItems() {
        show(SpaceExportFixtures.many(count = 60))
        composeRule.onNodeWithText(context.getString(R.string.space_export_start)).performTouchInput { click() }
        assertEquals(listOf("start"), calls)
    }
}
