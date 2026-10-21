package io.github.labushuya.emberlight.feature.space.retention

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.labushuya.emberlight.share.space.SpaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Empfänger für den Wecker der Erinnerung und für „1 Tag länger aufbewahren“ (Paket 3.6b). Nicht exportiert. Die Arbeit läuft
 * außerhalb des Haupt-Threads, die Meldung bleibt bis zum Ende offen.
 */
@AndroidEntryPoint
class RetentionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var reminders: RetentionReminders

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_CHECK -> {
                        reminders.checkNow(System.currentTimeMillis())
                    }

                    RetentionNotifier.ACTION_EXTEND -> {
                        val id = intent.getStringExtra(RetentionNotifier.EXTRA_SPACE_ID)?.let { SpaceId.parse(it) }
                        if (id != null) reminders.extendByOneDay(id, System.currentTimeMillis())
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "io.github.labushuya.emberlight.retention.CHECK"
    }
}
