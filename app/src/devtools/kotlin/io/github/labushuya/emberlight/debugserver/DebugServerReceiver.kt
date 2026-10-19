package io.github.labushuya.emberlight.debugserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.labushuya.emberlight.share.server.ParticipantSeed
import io.github.labushuya.emberlight.share.server.ServerSetup
import io.github.labushuya.emberlight.share.server.SpaceServerService
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.Principal
import io.github.labushuya.emberlight.share.space.Role
import io.github.labushuya.emberlight.share.space.SpaceId

/**
 * Debug-Einstieg für den Gerätetest des Servers (ADR 0014, docs/DEBUG-SERVER.md). Liegt im Source-Set `devtools` und ist nur
 * im Debug- und Benchmark-Build enthalten, nie im Release.
 *
 * START legt einen Test-Space mit einem Gastgeber und optional einem Gast an. Der PC übergibt die SHA-256-Hashes der Tokens
 * als Extras des Broadcasts (`host_token_sha256` Pflicht, `guest_token_sha256` optional, je 64 Hex-Zeichen). Klartext-Tokens
 * kommen auf dem Gerät nie an. Extras und Hashes erscheinen nie im Log.
 *
 * STOP beendet den Server über den Dienst.
 */
class DebugServerReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_START -> start(context, intent)
            ACTION_STOP -> context.startService(SpaceServerService.stopIntent(context))
            else -> Log.w(TAG, "Unbekannte Aktion ignoriert.")
        }
    }

    private fun start(
        context: Context,
        intent: Intent,
    ) {
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        if (port !in PORT_RANGE) {
            Log.e(TAG, "Debug-Server nicht gestartet: port liegt nicht zwischen 0 und 65535.")
            return
        }
        val check =
            DebugTokens.check(
                intent.getStringExtra(EXTRA_HOST_TOKEN_SHA256),
                intent.getStringExtra(EXTRA_GUEST_TOKEN_SHA256),
            )
        val hashes =
            when (check) {
                is TokenCheck.Accepted -> {
                    check
                }

                is TokenCheck.Rejected -> {
                    Log.e(TAG, "Debug-Server nicht gestartet: ${check.reason}")
                    return
                }
            }

        val spaceId = SpaceId.generate()
        val participants =
            buildList {
                add(seed(Role.HOST, HOST_NAME, hashes.host))
                hashes.guest?.let { add(seed(Role.GUEST, GUEST_NAME, it)) }
            }
        SpaceServerService.serverSetupProvider = {
            ServerSetup(
                spaceId = spaceId,
                spaceName = DEBUG_SPACE_NAME,
                participants = participants,
                debugLogging = true,
            )
        }
        ContextCompat.startForegroundService(context, SpaceServerService.startIntent(context, port))
        Log.i(TAG, "Debug-Server gestartet, Port $port, ${participants.size} Teilnehmer. Tokens stehen nicht im Log.")
    }

    /** Ein Teilnehmer. Der Hash kommt fertig vom PC und wird hier nur übernommen. */
    private fun seed(
        role: Role,
        displayName: String,
        tokenSha256Hex: String,
    ): ParticipantSeed =
        ParticipantSeed(
            principal = Principal(ParticipantId.generate(), role),
            displayName = displayName,
            tokenSha256Hex = tokenSha256Hex,
        )

    companion object {
        const val ACTION_START = "io.github.labushuya.emberlight.DEBUG_SERVER_START"
        const val ACTION_STOP = "io.github.labushuya.emberlight.DEBUG_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_HOST_TOKEN_SHA256 = "host_token_sha256"
        const val EXTRA_GUEST_TOKEN_SHA256 = "guest_token_sha256"
        const val DEFAULT_PORT = 8787

        // Dieselbe Log-Kennung wie der Dienst, damit `logcat -s EmberlightServer` alles zeigt.
        private const val TAG = "EmberlightServer"
        private const val DEBUG_SPACE_NAME = "Debug-Space"
        private const val HOST_NAME = "Test-Gastgeberin"
        private const val GUEST_NAME = "Test-Gast"
        private val PORT_RANGE = 0..65535
    }
}
