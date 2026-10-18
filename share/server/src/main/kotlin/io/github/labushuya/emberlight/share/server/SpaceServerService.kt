package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-Service, der den lokalen Server am Leben hält (ADR 0012). Typ `connectedDevice`, weil `dataSync` ein
 * Zeitlimit hat. Der Typ `connectedDevice` hat kein Zeitlimit.
 *
 * Solange der Server läuft, halten CPU-Wakelock und WLAN-Lock den Betrieb wach. Stopp (per Intent oder über die
 * Benachrichtigung) und `onDestroy` geben beide Sperren ausdrücklich frei. Ein Neustart nach Prozess-Tod erfolgt nicht
 * (`START_NOT_STICKY`).
 *
 * Start und Stopp laufen über [lifecycle] in der Reihenfolge, in der sie unter dem Mutex ankommen. Ein Stopp, der
 * während des Starts kommt, wartet, bis der Start fertig ist, und räumt danach Server und Sperren auf. Sperren werden
 * nur erworben, solange [stopRequested] nicht gesetzt ist.
 *
 * Noch ohne Routen und ohne Zugriffsprüfung (Paket 3.x): Der Server verweigert damit jede Anfrage. Die Oberfläche, die
 * den Dienst startet, folgt in Paket 3.1 bzw. 3.3.
 */
class SpaceServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialisiert Start, Stopp und Aufräumen. Verhindert, dass ein Stopp und ein laufender Start einander überholen. */
    private val lifecycle = Mutex()

    /** Gesetzt, sobald ein Stopp angefordert wurde. Start und Erwerb der Sperren prüfen das unter dem Mutex. */
    private val stopRequested = AtomicBoolean(false)

    /** Verhindert einen zweiten Startversuch, solange ein Start läuft oder der Server aktiv ist. */
    private val startRequested = AtomicBoolean(false)

    /** CPU- und WLAN-Sperre. Entsteht beim ersten erfolgreichen Start. */
    @Volatile
    private var locks: ServerLocks? = null

    private val server: LocalServer by lazy { serverFactory() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService. Auch unbekannte Aufrufe
        // erfüllen diese Pflicht, bevor der Dienst wieder endet.
        if (!ensureForeground()) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!startRequested.compareAndSet(false, true)) return START_NOT_STICKY
        if (!localNetworkAllowed()) {
            Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, Start abgelehnt.")
            requestStop()
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        scope.launch {
            lifecycle.withLock { startLocked(port) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sperren sofort lösen, auch wenn ein Start noch wartet. Ein wartender Start prüft [stopRequested] und erwirbt
        // dann nichts mehr.
        stopRequested.set(true)
        locks?.release()
        // Server und Sperren danach unter dem Mutex abschließen, dann den Scope beenden.
        scope
            .launch { lifecycle.withLock { shutDownLocked(stopSelfAfter = false) } }
            .invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    /** Fordert den Stopp an. Der eigentliche Abbau läuft unter dem Mutex, nach einem eventuell laufenden Start. */
    private fun requestStop() {
        stopRequested.set(true)
        scope.launch { lifecycle.withLock { shutDownLocked(stopSelfAfter = true) } }
    }

    /** Läuft unter [lifecycle]. Startet den Server und erwirbt die Sperren, außer ein Stopp steht an. */
    private suspend fun startLocked(port: Int) {
        if (stopRequested.get()) return
        try {
            val bound = server.start(ServerConfig(port = port))
            // Stopp kam während des Starts: Der wartende Stopp räumt auf, hier nichts mehr erwerben.
            if (stopRequested.get()) return
            acquireLocks()
            Log.i(TAG, "Server läuft auf Port $bound")
            updateNotification(bound)
        } catch (failure: Exception) {
            Log.e(TAG, "Start fehlgeschlagen: ${failure.javaClass.simpleName}")
            shutDownLocked(stopSelfAfter = true)
        }
    }

    /** Läuft unter [lifecycle]. Gibt Sperren frei, stoppt den Server und beendet den Dienst. */
    private suspend fun shutDownLocked(stopSelfAfter: Boolean) {
        locks?.release()
        try {
            server.stop()
        } catch (failure: Exception) {
            Log.e(TAG, "Stopp fehlgeschlagen: ${failure.javaClass.simpleName}")
        }
        startRequested.set(false)
        if (stopSelfAfter) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        val current = locks ?: locksFactory(this).also { locks = it }
        current.acquire()
    }

    @Suppress("DEPRECATION")
    internal fun createServerLocks(): ServerLocks {
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

    /**
     * Startet den Vordergrund-Betrieb. Liefert `false`, wenn Android den Start verweigert. Dann ist der Dienst nicht im
     * Vordergrund und der Aufrufer stoppt ihn.
     */
    private fun ensureForeground(): Boolean {
        createChannel()
        try {
            foregroundStarter.start(this, buildNotification(port = null), foregroundType())
        } catch (failure: SecurityException) {
            Log.e(TAG, "startForeground verweigert: SecurityException")
            return false
        } catch (failure: IllegalStateException) {
            if (!isForegroundStartNotAllowed(failure)) throw failure
            Log.e(TAG, "startForeground verweigert: ForegroundServiceStartNotAllowedException")
            return false
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            // Ohne Benachrichtigungs-Erlaubnis läuft der Dienst trotzdem, die Meldung bleibt unsichtbar.
            Log.w(TAG, "Benachrichtigungen sind nicht erlaubt, der Dienst läuft ohne sichtbare Meldung.")
        }
        return true
    }

    /** Ab API 31 kann Android den Vordergrund-Start aus dem Hintergrund ablehnen. Die Ausnahme gibt es erst ab dort. */
    private fun isForegroundStartNotAllowed(failure: IllegalStateException): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return failure is ForegroundServiceStartNotAllowedException
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
     * Prüft die Berechtigung `ACCESS_LOCAL_NETWORK`. Sie gilt nur, wenn das System API 37 oder neuer ist UND die App auf
     * targetSdk 37 oder neuer zielt. Andernfalls gilt die Prüfung als erfüllt. Die Anfrage selbst gehört in die
     * Oberfläche (ADR 0012, Paket 3.3).
     */
    private fun localNetworkAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17_SDK) return true
        if (applicationInfo.targetSdkVersion < ANDROID_17_SDK) return true
        return ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    /** Ein Aufruf von `startForeground`. Eigener Typ, damit Tests den Fehlerpfad auslösen können. */
    internal fun interface ForegroundStarter {
        fun start(
            service: SpaceServerService,
            notification: Notification,
            type: Int,
        )
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

        /** Test-Nähte. Produktion nutzt die Standardwerte. */
        internal var serverFactory: () -> LocalServer = { KtorLocalServer(routes = {}) }
        internal var locksFactory: (SpaceServerService) -> ServerLocks = { service -> service.createServerLocks() }
        internal var foregroundStarter: ForegroundStarter =
            ForegroundStarter { service, notification, type ->
                ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, type)
            }

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

// Ohne Timeout mit Absicht: Der Typ connectedDevice hat kein Zeitlimit. Die Sperre gilt, bis sie in Stopp oder onDestroy
// ausdrücklich freigegeben wird (siehe SpaceServerService.shutDownLocked und onDestroy).
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
