package io.github.labushuya.emberlight.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.labushuya.emberlight.share.server.spike.SpikeServerService
import java.io.File

/**
 * Nur Debug-Build (ADR 0012, Spike). Auswertung der adb-Extras, danach sofort `finish()`:
 *
 * - `--ez emberlight.spike.server true --es emberlight.spike.token <Token> --ei emberlight.spike.port 0`: starten
 * - `--ez emberlight.spike.stop true`: stoppen
 * - `--ez emberlight.spike.clean true`: stoppen und `filesDir/spike` löschen
 *
 * Der Token kommt vom PC und wird weder gespeichert noch geloggt.
 */
class SpikeDebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent
        when {
            extras.getBooleanExtra(EXTRA_CLEAN, false) -> {
                stopService()
                val removed = File(filesDir, SPIKE_DIR).deleteRecursively()
                Log.i(TAG, "Spike-Dateien gelöscht: $removed")
            }

            extras.getBooleanExtra(EXTRA_STOP, false) -> {
                stopService()
            }

            extras.getBooleanExtra(EXTRA_START, false) -> {
                val token = extras.getStringExtra(SpikeServerService.EXTRA_TOKEN)
                val port = extras.getIntExtra(SpikeServerService.EXTRA_PORT, 0)
                if (token.isNullOrEmpty()) {
                    Log.w(TAG, "Start ohne Token abgelehnt.")
                } else {
                    startForegroundService(SpikeServerService.startIntent(this, token, port))
                    Log.i(TAG, "Start angefordert, Port $port")
                }
            }

            else -> {
                Log.w(TAG, "Kein bekanntes Spike-Extra gesetzt.")
            }
        }
        finish()
    }

    private fun stopService() {
        startService(Intent(this, SpikeServerService::class.java).setAction(SpikeServerService.ACTION_STOP))
    }

    private companion object {
        const val TAG = "EmberlightSpike"
        const val SPIKE_DIR = "spike"
        const val EXTRA_START = "emberlight.spike.server"
        const val EXTRA_STOP = "emberlight.spike.stop"
        const val EXTRA_CLEAN = "emberlight.spike.clean"
    }
}
