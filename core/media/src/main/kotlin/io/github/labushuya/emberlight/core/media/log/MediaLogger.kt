package io.github.labushuya.emberlight.core.media.log

import android.util.Log
import javax.inject.Inject

/** Protokoll für unerwartete Fehler im Medienzugriff. Austauschbar, damit Tests ohne Android-Log auskommen. */
internal fun interface MediaLogger {
    fun warn(
        message: String,
        throwable: Throwable,
    )
}

/** Schreibt über `android.util.Log`. */
internal class LogcatMediaLogger
    @Inject
    constructor() : MediaLogger {
        override fun warn(
            message: String,
            throwable: Throwable,
        ) {
            Log.w(TAG, message, throwable)
        }

        private companion object {
            const val TAG = "MediaRepository"
        }
    }
