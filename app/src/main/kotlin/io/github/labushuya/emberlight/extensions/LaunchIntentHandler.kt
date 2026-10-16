package io.github.labushuya.emberlight.extensions

import android.content.Intent

/**
 * Auswertung eines Start-Intents durch zusätzliche Module, etwa den Demo-Schalter in Debug-Builds (ADR 0011). Die
 * Activity ruft alle Beiträge bei jedem Start und jedem neuen Intent auf. Im Release-Build gibt es keine Beiträge.
 */
interface LaunchIntentHandler {
    fun onLaunchIntent(intent: Intent)
}
