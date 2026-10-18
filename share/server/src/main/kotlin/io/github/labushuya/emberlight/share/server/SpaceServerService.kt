package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-Service, der den lokalen Server am Leben hält (ADR 0012). Typ `connectedDevice`, weil `dataSync` ein
 * Zeitlimit hat.
 *
 * Solange der Server läuft, halten CPU-Wakelock und WLAN-Lock den Betrieb wach. Stopp (per Intent oder über die
 * Benachrichtigung) gibt beide Sperren frei. Ein Neustart nach Prozess-Tod erfolgt nicht (`START_NOT_STICKY`).
 *
 * Noch ohne Routen und ohne Zugriffsprüfung (Paket 3.x): Der Server verweigert damit jede Anfrage. Die Oberfläche, die
 * den Dienst startet, folgt in Paket 3.1 bzw. 3.3.
 */
class SpaceServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Verhindert einen zweiten Startversuch, solange ein Start läuft oder der Server aktiv ist. */
    private val startRequested = AtomicBoolean(false)

    /** CPU- und WLAN-Sperre. Entsteht beim ersten erfolgreichen Start. */
    @Volatile
    private var locks: ServerLocks? = null

    private val server: LocalServer by lazy { KtorLocalServer(routes = {}) }

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
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService. Auch unbekannte Aufrufe
        // erfüllen diese Pflicht, bevor der Dienst wieder endet.
        ensureForeground()
        if (intent?.action != ACTION_START) {
            stopServer()
            return START_NOT_STICKY
        }
        if (!startRequested.compareAndSet(false, true)) return START_NOT_STICKY
        if (!localNetworkAllowed()) {
            Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, Start abgelehnt.")
            stopServer()
            return START_NOT_STICKY
        }

        val port = intent.getIntExtra(EXTRA_PORT, 0)
        scope.launch {
            try {
                val bound = server.start(ServerConfig(port = port))
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
        locks?.release()
        // Server zuerst sauber stoppen, dann den Scope beenden.
        scope.launch { server.stop() }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun stopServer() {
        scope.launch {
            locks?.release()
            server.stop()
            startRequested.set(false)
            ServiceCompat.stopForeground(this@SpaceServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val current = locks ?: createLocks().also { locks = it }
        current.acquire()
    }

    @Suppress("DEPRECATION")
    private fun createLocks(): ServerLocks {
        val power = getSystemService(PowerManager::class.java)
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        // WIFI_MODE_FULL_HIGH_PERF ist seit API 34 als veraltet markiert, ist aber der Modus, der auch bei ausgeschaltetem
        // Bildschirm greift. LOW_LATENCY wirkt nur bei eingeschaltetem Bildschirm im Vordergrund (ADR 0012).
        val wifiLock =
            wifi
                .createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    WIFI_LOCK_TAG,
                ).apply { setReferenceCounted(false) }
        return ServerLocks(wake.asServerLock(), wifiLock.asServerLock())
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
            // Ohne Benachrichtigungs-Erlaubnis läuft der Dienst trotzdem, die Meldung bleibt unsichtbar.
            Log.w(TAG, "Benachrichtigungen sind nicht erlaubt, der Dienst läuft ohne sichtbare Meldung.")
        }
    }

    private fun updateNotification(port: Int) {
        // Ab API 33 braucht die Benachrichtigung die Laufzeit-Erlaubnis. Ohne sie bleibt die Meldung aus.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(port))
    }

    /** Typ connectedDevice gibt es erst ab API 29. Darunter bleibt der Typ ungesetzt (0). */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.space_server_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int?): Notification {
        val stopPending =
            PendingIntent.getService(
                this,
                0,
                stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text =
            if (port == null) {
                getString(R.string.space_server_starting)
            } else {
                getString(R.string.space_server_port, port)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.space_server_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.space_server_stop), stopPending)
            .build()
    }

    /**
     * Ab targetSdk 37 ist lokaler Netzwerkzugriff eine Laufzeit-Berechtigung. Auf älteren Systemen gibt es sie nicht,
     * dort gilt die Prüfung als erfüllt. Die Anfrage selbst gehört in die Oberfläche (ADR 0012, Paket 3.3).
     */
    private fun localNetworkAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17_SDK) return true
        return ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_START = "io.github.labushuya.emberlight.share.server.START"
        const val ACTION_STOP = "io.github.labushuya.emberlight.share.server.STOP"
        const val EXTRA_PORT = "io.github.labushuya.emberlight.share.server.PORT"

        private const val TAG = "EmberlightServer"
        private const val WAKE_LOCK_TAG = "emberlight:space-server"
        private const val WIFI_LOCK_TAG = "emberlight:space-server-wifi"
        private const val CHANNEL_ID = "space_server"
        private const val NOTIFICATION_ID = 4711
        private const val ANDROID_17_SDK = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        /** Startintent. [port] 0 wählt einen freien Port. */
        fun startIntent(
            context: Context,
            port: Int = 0,
        ): Intent =
            Intent(context, SpaceServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)

        /** Stoppintent. Gibt die Sperren frei und beendet den Dienst. */
        fun stopIntent(context: Context): Intent =
            Intent(context, SpaceServerService::class.java).setAction(ACTION_STOP)
    }
}

// Ohne Timeout mit Absicht: die Sperre gilt, bis der Server stoppt. Der Dienst im Vordergrund begrenzt die Laufzeit.
@SuppressLint("WakelockTimeout")
private fun PowerManager.WakeLock.asServerLock(): ServerLock =
    object : ServerLock {
        override val isHeld: Boolean get() = this@asServerLock.isHeld

        override fun acquire() = this@asServerLock.acquire()

        override fun release() = this@asServerLock.release()
    }

private fun WifiManager.WifiLock.asServerLock(): ServerLock =
    object : ServerLock {
        override val isHeld: Boolean get() = this@asServerLock.isHeld

        override fun acquire() = this@asServerLock.acquire()

        override fun release() = this@asServerLock.release()
    }
