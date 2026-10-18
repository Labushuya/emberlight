package io.github.labushuya.emberlight.share.server.spike

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.labushuya.emberlight.share.server.AccessToken
import io.github.labushuya.emberlight.share.server.KtorLocalServer
import io.github.labushuya.emberlight.share.server.LocalServer
import io.github.labushuya.emberlight.share.server.R
import io.github.labushuya.emberlight.share.server.ServerConfig
import io.github.labushuya.emberlight.share.server.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Spike-Dienst (ADR 0012, nur Branch): hält den lokalen Server im Vordergrund, Typ `connectedDevice`.
 *
 * Start nur per Intent aus dem Debug-Source-Set. Der Token kommt vom PC als Extra und wird nicht geloggt. Die
 * Benachrichtigung zeigt den Port und hat eine Stopp-Aktion. Läuft der Prozess ohne Dienst weiter, ist der Server
 * weg, ein Neustart erfolgt nicht (`START_NOT_STICKY`).
 */
class SpikeServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Zeitpunkt des letzten Serverstarts, für `/health`. Wird vor dem Bind gesetzt, nicht beim Stopp. */
    @Volatile
    private var startedAtEpochMs: Long = 0L

    /** CPU- und WLAN-Sperre, solange der Server läuft. Entsteht beim ersten erfolgreichen Start. */
    @Volatile
    private var locks: SpikeLocks? = null

    /** Selbstprotokoll in filesDir/spike/diag.log (Herzschlag, Zustände, Anfragen). Wird in onCreate gestartet. */
    private val diagnostics: SpikeDiagnostics by lazy {
        SpikeDiagnostics(applicationContext, File(File(filesDir, SPIKE_DIR), SpikeDiagLog.FILE_NAME), scope) { locks }
    }

    private val server: LocalServer by lazy {
        KtorLocalServer(
            routes =
                spikeRoutes(File(filesDir, SPIKE_DIR)) { bytes ->
                    Log.i(TAG, "Upload-Fortschritt: $bytes Byte")
                },
            health = {
                val held = locks
                spikeHealthJson(
                    pid = Process.myPid(),
                    startedAtEpochMs = startedAtEpochMs,
                    nowEpochMs = System.currentTimeMillis(),
                    wakeLockHeld = held?.cpuHeld ?: false,
                    wifiLockHeld = held?.wifiHeld ?: false,
                    lastHeartbeatEpochMs = diagnostics.lastHeartbeatEpochMs,
                )
            },
            requestLog = { diagnostics.record(it) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        diagnostics.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService.
        ensureForeground()
        if (server.state.value is ServerState.Running) return START_NOT_STICKY

        val rawToken = intent?.getStringExtra(EXTRA_TOKEN)
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val token = runCatching { AccessToken.of(rawToken.orEmpty()) }.getOrNull()
        if (token == null) {
            Log.w(TAG, "Kein gültiger Token im Intent, Dienst endet.")
            stopServer()
            return START_NOT_STICKY
        }
        if (!localNetworkAllowed()) {
            Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, Start abgelehnt.")
            stopServer()
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                // Alte Spike-Dateien aus früheren Läufen entfernen, das Diagnoseprotokoll bleibt. Der Server läuft hier nicht, also greift niemand zu.
                File(filesDir, SPIKE_DIR)
                    .listFiles()
                    .orEmpty()
                    .filter { it.name != SpikeDiagLog.FILE_NAME }
                    .forEach { it.deleteRecursively() }
                startedAtEpochMs = System.currentTimeMillis()
                val bound = server.start(ServerConfig(port = port, token = token))
                acquireLocks()
                Log.i(TAG, "Server läuft auf Port $bound")
                updateNotification(bound)
            } catch (failure: Exception) {
                Log.e(TAG, "Start fehlgeschlagen: ${failure.javaClass.simpleName}")
                stopServer()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sperren zuerst lösen, damit sie auch bei abgebrochenem Stopp nicht hängen bleiben.
        diagnostics.stop()
        locks?.release()
        // Server zuerst sauber stoppen, dann den Scope beenden.
        scope.launch { server.stop() }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun stopServer() {
        scope.launch {
            locks?.release()
            server.stop()
            ServiceCompat.stopForeground(this@SpikeServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Hält CPU-Wakelock und WLAN-Lock, bis der Server gestoppt wird. Ohne Timeout (Spike, Bildschirm-aus-Test). */
    private fun acquireLocks() {
        val current = locks ?: createLocks().also { locks = it }
        current.acquire()
    }

    @Suppress("DEPRECATION")
    private fun createLocks(): SpikeLocks {
        val power = getSystemService(PowerManager::class.java)
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        // WIFI_MODE_FULL_HIGH_PERF ist ab API 34 als veraltet markiert, ist aber der einzige Modus ohne die Bedingung
        // "Bildschirm an" (LOW_LATENCY greift nur bei eingeschaltetem Bildschirm im Vordergrund). Spike-Entscheidung, siehe Bericht.
        val wifiLock =
            wifi
                .createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    WIFI_LOCK_TAG,
                ).apply { setReferenceCounted(false) }
        return SpikeLocks(wake.asSpikeLock(), wifiLock.asSpikeLock())
    }

    private fun ensureForeground() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(port = null),
            foregroundType(),
        )
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            // Ohne POST_NOTIFICATIONS läuft der Dienst trotzdem, die Benachrichtigung bleibt aber unsichtbar.
            Log.w(TAG, "Benachrichtigungen sind nicht erlaubt, der Dienst läuft ohne sichtbare Meldung.")
        }
    }

    private fun updateNotification(port: Int) {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(port))
    }

    /** Typ connectedDevice gibt es erst ab API 29. Darunter bleibt der Typ ungesetzt (0). */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.spike_server_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW,
            )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int?): Notification {
        val stopIntent = Intent(this, SpikeServerService::class.java).setAction(ACTION_STOP)
        val stopPending =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text =
            if (port == null) {
                getString(R.string.spike_server_starting)
            } else {
                getString(R.string.spike_server_port, port)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.spike_server_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.spike_server_stop), stopPending)
            .build()
    }

    /**
     * Ab targetSdk 37 ist lokaler Netzwerkzugriff eine Runtime-Berechtigung. Auf älteren Systemen gibt es sie nicht,
     * dort gilt die Prüfung als erfüllt. Die Anfrage selbst gehört in die Oberfläche (siehe ADR 0012, Android 17).
     */
    private fun localNetworkAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17_SDK) return true
        return ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_STOP = "io.github.labushuya.emberlight.spike.STOP"
        const val EXTRA_TOKEN = "emberlight.spike.token"
        const val EXTRA_PORT = "emberlight.spike.port"

        private const val TAG = "EmberlightSpike"
        private const val WAKE_LOCK_TAG = "emberlight:space-server"
        private const val WIFI_LOCK_TAG = "emberlight:space-server-wifi"
        private const val CHANNEL_ID = "spike_server"
        private const val NOTIFICATION_ID = 4711
        private const val SPIKE_DIR = "spike"
        private const val ANDROID_17_SDK = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        /** Baut den Startintent. Der Aufrufer reicht den Token durch, der Dienst speichert ihn nicht. */
        fun startIntent(
            context: Context,
            token: String,
            port: Int,
        ): Intent =
            Intent(context, SpikeServerService::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_PORT, port)
    }
}

/**
 * Ohne Timeout mit Absicht: der Spike hält die Sperre bis zum Stopp des Servers (Bildschirm-aus-Test). Der Dienst im
 * Vordergrund begrenzt die Laufzeit.
 */
@SuppressLint("WakelockTimeout")
private fun PowerManager.WakeLock.asSpikeLock(): SpikeLock =
    object : SpikeLock {
        override val isHeld: Boolean get() = this@asSpikeLock.isHeld

        override fun acquire() = this@asSpikeLock.acquire()

        override fun release() = this@asSpikeLock.release()
    }

private fun WifiManager.WifiLock.asSpikeLock(): SpikeLock =
    object : SpikeLock {
        override val isHeld: Boolean get() = this@asSpikeLock.isHeld

        override fun acquire() = this@asSpikeLock.acquire()

        override fun release() = this@asSpikeLock.release()
    }
