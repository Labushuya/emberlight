package io.github.labushuya.emberlight.feature.space

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Obergrenze für das Warten auf einen asynchronen Zustand (Room, Hintergrund-Dispatcher). */
internal const val STATE_TIMEOUT_MILLIS = 5_000L

/**
 * Wartet, bis [state] das Prädikat erfüllt, und liefert diesen Zustand. Der Test suspendiert dabei, bis der nächste Wert
 * ankommt, und fragt nicht in Schleifen mit festen Pausen ab. Bleibt das Prädikat aus, schlägt der Test mit dem zuletzt
 * gesehenen Zustand fehl.
 */
internal fun <T> awaitValue(
    state: StateFlow<T>,
    timeoutMillis: Long = STATE_TIMEOUT_MILLIS,
    predicate: (T) -> Boolean,
): T =
    try {
        runBlocking { withTimeout(timeoutMillis) { state.first(predicate) } }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("Zustand wurde nicht erreicht: ${state.value}", e)
    }
