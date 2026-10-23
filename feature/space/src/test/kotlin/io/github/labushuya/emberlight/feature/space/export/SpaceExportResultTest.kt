package io.github.labushuya.emberlight.feature.space.export

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ergebnis der Übernahme zeigt jeden Fehlergrund mit Anzahl-Satz (ADR 0022: „Fehler mit Grund“). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-w412dp-h915dp")
class SpaceExportResultTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyFailureReasonIsShown() {
        val result =
            ExportResultUi(
                exported = 3,
                skipped = 1,
                failed = 3,
                failedHash = 1,
                failedWrite = 1,
                failedMissing = 1,
                failedUnknown = 1,
                stoppedByPurge = false,
            )
        val state = SpaceExportUiState(loading = false, spaceName = "Sommerfest", phase = ExportPhase.Finished(result))
        composeRule.setContent {
            EmberlightTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpaceExportContent(
                        state = state,
                        onBack = {},
                        onAll = {},
                        onNone = {},
                        onToggle = {},
                        onStart = {},
                        onCancel = {},
                    )
                }
            }
        }
        listOf(
            R.string.space_export_failed_hash,
            R.string.space_export_failed_write,
            R.string.space_export_failed_missing,
            R.string.space_export_failed_unknown,
        ).forEach { reason ->
            composeRule.onNodeWithText(context.getString(reason)).assertIsDisplayed()
        }
    }
}
