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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-Service, der den lokalen Server am Leben hält (ADR 0012, ADR 0013). Typ `connectedDevice`, weil `dataSync`
 * ein Zeitlimit hat. Der Typ `connectedDevice` hat kein Zeitlimit.
 *
 * Ablauf eines Starts (unter [lifecycle]):
 * 1. Einrichtung holen ([serverSetupProvider]). Ohne Einrichtung startet der Dienst nicht.
 * 2. WLAN-Adresse holen ([networkFactory]). Ohne WLAN mit Adresse startet der Dienst nicht.
 * 3. Index und Speicher öffnen, Server an die WLAN-Adresse binden, Sperren erwerben.
 * 4. Netzwechsel beobachten. Jede Änderung der Adresse oder der Verlust des WLAN stoppt den Server (ADR 0013, Netz).
 *    Der Dienst startet nicht selbst neu.
 *
 * Solange der Server läuft, halten CPU-Wakelock und WLAN-Lock den Betrieb wach. Stopp (per Intent oder Benachrichtigung),
 * Netzwechsel und `onDestroy` geben beide Sperren frei. Ein Neustart nach Prozess-Tod erfolgt nicht (`START_NOT_STICKY`).
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

    private var activeServer: LocalServer? = null
    private var activeRuntime: SpaceRuntime? = null
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop(alertText = null)
            return START_NOT_STICKY
        }
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService. Auch unbekannte Aufrufe
        // erfüllen diese Pflicht, bevor der Dienst wieder endet.
        if (!ensureForeground()) {
            requestStop(alertText = null)
            return START_NOT_STICKY
        }
        if (intent == null || intent.action != ACTION_START) {
            requestStop(alertText = null)
            return START_NOT_STICKY
        }
        if (!startRequested.compareAndSet(false, true)) return START_NOT_STICKY
        if (!localNetworkAllowed()) {
            Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, Start abgelehnt.")
            requestStop(alertText = null)
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
            .launch { lifecycle.withLock { shutDownLocked(stopSelfAfter = false, alertText = null) } }
            .invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    /** Fordert den Stopp an. Der eigentliche Abbau läuft unter dem Mutex, nach einem eventuell laufenden Start. */
    private fun requestStop(alertText: Int?) {
        stopRequested.set(true)
        scope.launch { lifecycle.withLock { shutDownLocked(stopSelfAfter = true, alertText = alertText) } }
    }

    /** Läuft unter [lifecycle]. Richtet den Server ein, bindet ihn an das WLAN und erwirbt die Sperren. */
    private suspend fun startLocked(port: Int) {
        if (stopRequested.get()) return
        val setup = serverSetupProvider()
        if (setup == null) {
            Log.w(TAG, "Kein Space eingerichtet, der Server startet nicht.")
            shutDownLocked(stopSelfAfter = true, alertText = R.string.space_server_no_space)
            return
        }
        val provider = networkFactory(this)
        val wifi = provider.currentAddress()
        if (wifi == null) {
            Log.w(TAG, "Kein WLAN mit IPv4-Adresse, der Server startet nicht.")
            shutDownLocked(stopSelfAfter = true, alertText = R.string.space_server_no_wifi)
            return
        }
        try {
            val runtime = SpaceRuntime.open(applicationContext, setup)
            activeRuntime = runtime
            val server = serverFactory(runtime, setup)
            activeServer = server
            val bound = server.start(ServerConfig(port = port, subnet = wifi.subnet, debugLogging = setup.debugLogging))
            // Stopp kam während des Starts: Der wartende Stopp räumt auf, hier nichts mehr erwerben.
            if (stopRequested.get()) return
            acquireLocks()
            watchNetwork(provider, wifi)
            Log.i(TAG, "Server läuft auf Port $bound")
            if (setup.debugLogging) Log.i(TAG, "Debug: gebunden an ${wifi.ipv4}:$bound")
            updateNotification(bound)
        } catch (failure: Exception) {
            Log.e(TAG, "Start fehlgeschlagen: ${failure.javaClass.simpleName}")
            shutDownLocked(stopSelfAfter = true, alertText = null)
        }
    }

    /** Stoppt den Server, sobald sich die WLAN-Adresse ändert oder das WLAN verschwindet (ADR 0013, Netz). */
    private fun watchNetwork(
        provider: WifiNetworkProvider,
        bound: WifiAddress,
    ) {
        watchJob =
            scope.launch {
                provider
                    .changes()
                    .filter { it != bound }
                    .collect {
                        Log.w(TAG, "WLAN-Adresse hat sich geändert, der Server stoppt.")
                        requestStop(alertText = R.string.space_server_network_changed)
                    }
            }
    }

    /** Läuft unter [lifecycle]. Gibt Sperren frei, stoppt den Server und beendet den Dienst. */
    private suspend fun shutDownLocked(
        stopSelfAfter: Boolean,
        alertText: Int?,
    ) {
        watchJob?.cancel()
        watchJob = null
        locks?.release()
        try {
            activeServer?.stop()
        } catch (failure: Exception) {
            Log.e(TAG, "Stopp fehlgeschlagen: ${failure.javaClass.simpleName}")
        }
        activeServer = null
        activeRuntime?.close()
        activeRuntime = null
        startRequested.set(false)
        alertText?.let { postAlert(it) }
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
        createChannels()
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

    /** Hinweis an den Gastgeber, etwa bei Netzwechsel oder fehlendem WLAN. Eigener Kanal, nicht die Dauermeldung. */
    private fun postAlert(text: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification =
            NotificationCompat
                .Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.space_server_running_title))
                .setContentText(getString(text))
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, notification)
    }

    /** Typ connectedDevice gibt es erst ab API 29. Darunter bleibt der Typ ungesetzt (0). */
    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    private fun createChannels() {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.space_server_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.space_server_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
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
        private const val ALERT_CHANNEL_ID = "space_server_alerts"
        private const val NOTIFICATION_ID = 4711
        private const val ALERT_NOTIFICATION_ID = 4712
        private const val ANDROID_17_SDK = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        /**
         * Einrichtung des Serverlaufs. Produktion: keine (`null`), der Dienst startet nicht. Der Debug-Einstieg setzt sie
         * nur im Debug-Build (ADR 0014, Debug-Einstieg).
         */
        @Volatile
        var serverSetupProvider: () -> ServerSetup? = { null }

        /** Test-Nähte. Produktion nutzt die Standardwerte. */
        internal var serverFactory: (SpaceRuntime, ServerSetup) -> LocalServer = { runtime, setup ->
            val gates = ServerGates()
            KtorLocalServer(
                routes = spaceRoutes(runtime.spaceVault, setup.spaceName, gates),
                authorizer = runtime.authorizer,
                requestLog = { line -> if (setup.debugLogging) Log.i(TAG, line) },
                gates = gates,
            )
        }
        internal var networkFactory: (Context) -> WifiNetworkProvider = { context ->
            ConnectivityWifiNetworkProvider(context)
        }
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
