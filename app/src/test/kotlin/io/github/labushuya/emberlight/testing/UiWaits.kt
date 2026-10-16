package io.github.labushuya.emberlight.testing

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

/**
 * Obergrenze für jedes Warten auf die Oberfläche. Entscheidend ist die Bedingung: Sie gilt, sobald die Oberfläche so
 * weit ist, und dann geht der Test sofort weiter. Die Grenze fängt nur einen Zustand ab, der nie eintritt.
 */
internal const val UI_TIMEOUT_MS = 5_000L

/**
 * Wartet, bis die Prüfungen in [assertions] ohne Fehler durchlaufen. Navigationsübergänge, Einblendungen und das
 * Laden der ViewModels lassen eine Prüfung kurz fehlschlagen, deshalb wird wiederholt, nicht festgelegt, wie lange
 * es dauert. Gilt die Bedingung bis zur Grenze nicht, wird die letzte Compose-Fehlermeldung geworfen. Sie beschreibt
 * den Zustand der Knoten zum Fehlerzeitpunkt.
 */
internal fun ComposeTestRule.awaitUi(assertions: ComposeTestRule.() -> Unit) {
    var lastFailure: AssertionError? = null
    val waited =
        runCatching {
            waitUntil(UI_TIMEOUT_MS) {
                try {
                    assertions()
                    true
                } catch (failure: AssertionError) {
                    lastFailure = failure
                    false
                }
            }
        }
    waited.exceptionOrNull()?.let { throw lastFailure ?: it }
}

/** Wartet, bis genau ein Knoten mit [matcher] an seiner Stelle angezeigt wird. */
internal fun ComposeTestRule.awaitDisplayed(matcher: SemanticsMatcher) {
    awaitUi { onNode(matcher).assertIsDisplayed() }
}

/** Wartet, bis kein Knoten mit [matcher] mehr im Baum steht. */
internal fun ComposeTestRule.awaitAbsent(matcher: SemanticsMatcher) {
    awaitUi { onAllNodes(matcher).assertCountEquals(0) }
}

/** Wartet, bis der Knoten angezeigt wird, und klickt dann auf ihn. */
internal fun ComposeTestRule.clickWhenDisplayed(matcher: SemanticsMatcher) {
    awaitDisplayed(matcher)
    onNode(matcher).performClick()
}
