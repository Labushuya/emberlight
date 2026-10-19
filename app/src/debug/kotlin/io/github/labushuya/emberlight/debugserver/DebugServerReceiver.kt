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
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Debug-Einstieg für den Gerätetest des Servers (ADR 0014, docs/DEBUG-SERVER.md). Nur im Debug-Build.
 *
 * START legt einen Test-Space mit einem Gastgeber und optional einem Gast an. Jeder Teilnehmer bekommt einen zufälligen
 * Token (256 bit). Im Speicher der App landen nur die Hashes. Die Klartext-Tokens stehen in einer Datei unter
 * `files/debug-server/tokens.json`, die nur per `run-as` lesbar ist. Sie erscheinen nie im Log.
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
        val withGuest = intent.getBooleanExtra(EXTRA_GUEST, false)
        val spaceId = SpaceId.generate()
        val host = Participant(ParticipantId.generate(), Role.HOST, "Test-Gastgeberin")
        val guest = if (withGuest) Participant(ParticipantId.generate(), Role.GUEST, "Test-Gast") else null
        val participants = listOfNotNull(host, guest)

        writeTokenFile(context, spaceId, port, participants)
        SpaceServerService.serverSetupProvider = {
            ServerSetup(
                spaceId = spaceId,
                spaceName = DEBUG_SPACE_NAME,
                participants =
                    participants.map { ParticipantSeed(it.principal, it.displayName, it.tokenSha256Hex) },
                debugLogging = true,
            )
        }
        ContextCompat.startForegroundService(context, SpaceServerService.startIntent(context, port))
        Log.i(TAG, "Debug-Server gestartet, Port $port. Tokens: files/debug-server/tokens.json (nicht im Log).")
    }

    /** Ein Teilnehmer mit Klartext-Token. Der Token verlässt diese Klasse nur in die Datei. */
    private class Participant(
        val id: ParticipantId,
        val role: Role,
        val displayName: String,
    ) {
        val token: String = randomToken()
        val tokenSha256Hex: String = sha256Hex(token)
        val principal = Principal(id, role)
    }

    private fun writeTokenFile(
        context: Context,
        spaceId: SpaceId,
        port: Int,
        participants: List<Participant>,
    ) {
        val json =
            JSONObject()
                .put("spaceId", spaceId.value)
                .put("port", port)
                .put("note", "Nur für den Gerätetest. Nie in Logs, nie in Repository.")
        participants.forEach { participant ->
            json.put(
                participant.role.name.lowercase(),
                JSONObject()
                    .put("participantId", participant.id.value)
                    .put("token", participant.token),
            )
        }
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        File(directory, TOKEN_FILE).writeText(json.toString(2), Charsets.UTF_8)
    }

    companion object {
        const val ACTION_START = "io.github.labushuya.emberlight.DEBUG_SERVER_START"
        const val ACTION_STOP = "io.github.labushuya.emberlight.DEBUG_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_GUEST = "guest"
        const val DEFAULT_PORT = 8787
        private const val TAG = "EmberlightDebug"
        private const val DIRECTORY = "debug-server"
        private const val TOKEN_FILE = "tokens.json"
        private const val DEBUG_SPACE_NAME = "Debug-Space"
        private const val TOKEN_BYTES = 32

        private fun randomToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun sha256Hex(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
