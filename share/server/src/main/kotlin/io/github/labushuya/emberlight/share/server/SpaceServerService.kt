package io.github.labushuya.emberlight.share.server

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.SpaceLimits
import io.github.labushuya.emberlight.share.space.SpacesDatabase
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.RunClock
import io.github.labushuya.emberlight.share.space.run.SpaceRunRepository
import io.github.labushuya.emberlight.share.space.run.SystemRunClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground-Service für den lokalen Server (ADR 0012, ADR 0013, ADR 0017). Typ `connectedDevice`, weil `dataSync` ein
 * Zeitlimit hat. Der Dienst selbst ist dünn: Er übersetzt Intents in Befehle für den [SpaceRunner], der Start, Stopp,
 * Pausen und Fortsetzen seriell abarbeitet. Er stellt die Umgebung bereit: Sperren, Wecker, Netz, Benachrichtigungen.
 *
 * Solange der Server läuft, halten CPU-Wakelock und WLAN-Lock den Betrieb wach. Der Dienst startet nach einem
 * Prozess-Tod nicht neu (`START_NOT_STICKY`). Ein aktiver Space, der keinen Dienst mehr hat, wird beim nächsten
 * Start der App abgeschlossen (siehe [closeOrphanedSpaces]).
 */
class SpaceServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = ServiceHost()
    private val runner = SpaceRunner(scope, SystemRunClock, host, runStateFlow)
    private lateinit var runnerJob: Job

    /** Kennung des letzten Intents. `stopSelf` mit ihr beendet den Dienst nur, wenn kein neuerer Auftrag wartet. */
    @Volatile
    private var lastStartId = 0

    /** CPU- und WLAN-Sperre. Entsteht beim ersten erfolgreichen Start. */
    @Volatile
    private var locks: ServerLocks? = null

    private val networkProvider: WifiNetworkProvider by lazy { networkFactory(this) }

    override fun onCreate() {
        super.onCreate()
        runnerJob = runner.run()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                runner.submit(RunCommand.Stop(StopReason.USER))
                return START_NOT_STICKY
            }

            ACTION_EXPIRE -> {
                runner.submit(RunCommand.Expire)
                return START_NOT_STICKY
            }

            ACTION_CONFIRM_NETWORK -> {
                runner.submit(RunCommand.ConfirmNetwork)
                return START_NOT_STICKY
            }
        }
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService. Auch unbekannte Aufrufe
        // erfüllen diese Pflicht, bevor der Dienst wieder endet.
        if (!ensureForeground()) {
            Log.e(TAG, "Vordergrund-Start verweigert, der Dienst endet.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            // Unbekannter Aufruf ohne Auftrag: Der Dienst endet, wenn kein Space läuft.
            if (runState.value is RunState.Idle) stopSelf(startId)
            return START_NOT_STICKY
        }
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)?.let(::SpaceId) ?: SpaceId.generate()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        runner.submit(RunCommand.Start(spaceId, port))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Sperren sofort lösen. Der Verarbeiter beendet danach einen laufenden Server und schließt den Kanal.
        locks?.release()
        runner.shutdown()
        runnerJob.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    /** Umgebung des Verarbeiters. Alles, was Android braucht, läuft hier. */
    private inner class ServiceHost : RunHost {
        private val runs: SpaceRunRepository by lazy {
            SpaceRunRepository(SpacesDatabase.shared(applicationContext), SystemRunClock)
        }

        override suspend fun setupFor(spaceId: SpaceId): ServerSetup? {
            if (!localNetworkAllowed()) {
                Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, der Start ist abgelehnt.")
                // Der Space ist schon aktiv, weil die Oberfläche ihn vorher gestartet hat. Er geht zurück auf Entwurf.
                runs.abortStart(spaceId)
                return null
            }
            serverSetupProvider()?.let { return it }
            val record = runs.findActive()?.takeIf { it.id == spaceId } ?: return null
            return record.toServerSetup()
        }

        override suspend fun openServer(
            setup: ServerSetup,
            closure: SpaceClosure,
        ): OpenedServer {
            val runtime = SpaceRuntime.open(applicationContext, setup)
            return OpenedServer(serverFactory(runtime, setup, closure), release = {})
        }

        override fun currentWifi(): WifiAddress? = networkProvider.currentAddress()

        override fun wifiChanges(): Flow<WifiAddress?> = networkProvider.changes()

        override fun acquireLocks() {
            val current = locks ?: locksFactory(this@SpaceServerService).also { locks = it }
            current.acquire()
        }

        override fun releaseLocks() {
            locks?.release()
        }

        override fun scheduleExpiry(atMillis: Long) {
            alarmManager().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, expiryIntentPending())
        }

        override fun cancelExpiry() {
            alarmManager().cancel(expiryIntentPending())
        }

        override suspend fun endSpace(
            spaceId: SpaceId,
            endedAtMillis: Long,
        ) {
            runs.end(spaceId, endedAtMillis)
        }

        override suspend fun abortStart(spaceId: SpaceId) {
            runs.abortStart(spaceId)
        }

        override suspend fun recordPause(
            spaceId: SpaceId,
            event: PauseEvent,
        ) {
            runs.recordPause(spaceId, event)
        }

        override fun onEvent(event: RunEvent) {
            when (event) {
                RunEvent.NotStartable -> {
                    postAlert(getString(R.string.space_server_not_startable))
                }

                RunEvent.NoWifi -> {
                    postAlert(getString(R.string.space_server_no_wifi))
                }

                RunEvent.StartFailed -> {
                    postAlert(getString(R.string.space_server_start_failed))
                }

                RunEvent.ResumeFailed -> {
                    postAlert(getString(R.string.space_server_resume_failed))
                }

                is RunEvent.PauseDetected -> {
                    postAlert(
                        getString(R.string.space_server_pause_detected, minutesOf(event.event)),
                        route = null,
                    )
                }

                RunEvent.NetworkLost -> {
                    postAlert(getString(R.string.space_server_network_lost))
                }

                is RunEvent.NetworkChanged -> {
                    postAlert(
                        getString(R.string.space_server_network_changed_prompt),
                        route = ROUTE_CONFIRM_NETWORK,
                    )
                }
            }
        }

        override fun onRunningTick(state: RunState.Running) {
            updateOngoing(ongoingText(state.spaceName, state.expiresAtMillis))
        }

        override fun onIdle() {
            // Nur die Dauermeldung entfernen und den Dienst beenden. Ein neuerer Auftrag verhindert das Beenden.
            ServiceCompat.stopForeground(this@SpaceServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** Einrichtung eines gespeicherten, aktiven Space für den Server. */
    private fun SpaceRecord.toServerSetup(): ServerSetup =
        ServerSetup(
            spaceId = id,
            spaceName = settings.name,
            limits = SpaceLimits(storageLimitBytes = settings.storageLimitBytes),
            startedAtMillis = startedAt,
            expiresAtMillis = expiresAt,
            persisted = true,
        )

    /**
     * Prüft die Berechtigung `ACCESS_LOCAL_NETWORK`. Sie gilt nur, wenn das System API 37 oder neuer ist UND die App auf
     * targetSdk 37 oder neuer zielt. Andernfalls gilt die Prüfung als erfüllt (ADR 0012, Paket 3.3).
     */
    private fun localNetworkAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17_SDK) return true
        if (applicationInfo.targetSdkVersion < ANDROID_17_SDK) return true
        return ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
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
     * Vordergrund und der Aufrufer beendet ihn.
     */
    private fun ensureForeground(): Boolean {
        createChannels()
        try {
            foregroundStarter.start(this, buildOngoing(getString(R.string.space_server_starting)), foregroundType())
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

    // Die Berechtigung prüft canPostNotifications() vor dem Aufruf. Lint erkennt die Prüfung über die Methode nicht.
    @SuppressLint("MissingPermission")
    private fun updateOngoing(text: String) {
        if (!canPostNotifications()) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildOngoing(text))
    }

    /** Text der Dauermeldung: „Space ‚Name‘ läuft · noch X“. Ohne Ende nur der Name. */
    private fun ongoingText(
        spaceName: String,
        expiresAtMillis: Long?,
    ): String =
        if (expiresAtMillis == null) {
            getString(R.string.space_server_no_end_text, spaceName)
        } else {
            val remaining = expiresAtMillis - SystemRunClock.wallMillis()
            getString(R.string.space_server_ongoing_text, spaceName, remainingText(remaining))
        }

    private fun remainingText(millis: Long): String {
        val minutes = millis / MILLIS_PER_MINUTE
        return when {
            millis < MILLIS_PER_MINUTE -> {
                getString(R.string.space_server_remaining_under_minute)
            }

            minutes < MINUTES_PER_HOUR -> {
                getString(R.string.space_server_remaining_m, minutes)
            }

            else -> {
                getString(
                    R.string.space_server_remaining_hm,
                    minutes / MINUTES_PER_HOUR,
                    minutes % MINUTES_PER_HOUR,
                )
            }
        }
    }

    /**
     * Hinweis an den Gastgeber: Fehler, Pause, Netzwechsel. Mit [route] öffnet ein Tippen die passende Stelle der App.
     * Eigener Kanal, nicht die Dauermeldung. Die Berechtigung prüft [canPostNotifications] vor dem Aufruf. Lint erkennt
     * die Prüfung über die Methode nicht, deshalb die Unterdrückung.
     */
    @SuppressLint("MissingPermission")
    private fun postAlert(
        text: String,
        route: String? = null,
    ) {
        if (!canPostNotifications()) return
        val builder =
            NotificationCompat
                .Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.space_server_running_title))
                .setContentText(text)
                .setAutoCancel(true)
        launchPendingIntent(route ?: ROUTE_OPEN_SPACE, REQUEST_OPEN_SPACE)?.let(builder::setContentIntent)
        NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, builder.build())
    }

    /** Dauermeldung des Dienstes. Tippen öffnet den Space. „Beenden“ öffnet die App mit Bestätigung, nie direkt. */
    private fun buildOngoing(text: String): Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.space_server_running_title))
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
        launchPendingIntent(ROUTE_OPEN_SPACE, REQUEST_OPEN_SPACE)?.let { pending ->
            builder.setContentIntent(pending)
            builder.addAction(0, getString(R.string.space_server_open), pending)
        }
        launchPendingIntent(ROUTE_CONFIRM_END, REQUEST_CONFIRM_END)?.let { pending ->
            builder.addAction(0, getString(R.string.space_server_end), pending)
        }
        return builder.build()
    }

    /** Öffnet die App über ihren Starteintrag mit einem Ziel. Liefert `null`, wenn es keinen Starteintrag gibt. */
    private fun launchPendingIntent(
        route: String,
        requestCode: Int,
    ): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.putExtra(EXTRA_ROUTE, route)
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            requestCode,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun expiryIntentPending(): PendingIntent =
        PendingIntent.getService(
            this,
            REQUEST_EXPIRY,
            expireIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alarmManager(): AlarmManager = getSystemService(AlarmManager::class.java)

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED

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
        const val ACTION_EXPIRE = "io.github.labushuya.emberlight.share.server.EXPIRE"
        const val ACTION_CONFIRM_NETWORK = "io.github.labushuya.emberlight.share.server.CONFIRM_NETWORK"
        const val EXTRA_PORT = "io.github.labushuya.emberlight.share.server.PORT"
        const val EXTRA_SPACE_ID = "io.github.labushuya.emberlight.share.server.SPACE_ID"

        /** Ziel, das die App beim Öffnen per Benachrichtigung bekommt. Die App wertet es aus. */
        const val EXTRA_ROUTE = "io.github.labushuya.emberlight.share.server.ROUTE"
        const val ROUTE_OPEN_SPACE = "open_space"
        const val ROUTE_CONFIRM_END = "confirm_end"
        const val ROUTE_CONFIRM_NETWORK = "confirm_network"

        private const val TAG = "EmberlightServer"
        private const val WAKE_LOCK_TAG = "emberlight:space-server"
        private const val WIFI_LOCK_TAG = "emberlight:space-server-wifi"
        private const val CHANNEL_ID = "space_server"
        private const val ALERT_CHANNEL_ID = "space_server_alerts"
        private const val NOTIFICATION_ID = 4711
        private const val ALERT_NOTIFICATION_ID = 4712
        private const val REQUEST_OPEN_SPACE = 1
        private const val REQUEST_CONFIRM_END = 2
        private const val REQUEST_EXPIRY = 3
        private const val ANDROID_17_SDK = 37
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

        /** Zustand des Betriebs für die Oberfläche. Prozessweit, weil der Dienst nur einmal läuft. */
        private val runStateFlow = MutableStateFlow<RunState>(RunState.Idle)

        /** Zustand des Betriebs. Die Oberfläche beobachtet ihn für die Anzeige und den Netzwechsel-Hinweis. */
        val runState: StateFlow<RunState> = runStateFlow.asStateFlow()

        /**
         * Einrichtung des Serverlaufs ohne gespeicherten Space. Nur der Debug-Einstieg setzt sie (ADR 0014). Ist sie
         * gesetzt, nimmt der Dienst sie vor dem gespeicherten Space.
         */
        @Volatile
        var serverSetupProvider: () -> ServerSetup? = { null }

        /** Test-Nähte. Produktion nutzt die Standardwerte. */
        internal var serverFactory: (SpaceRuntime, ServerSetup, SpaceClosure) -> LocalServer =
            { runtime, setup, closure ->
                val gates = ServerGates()
                KtorLocalServer(
                    routes = spaceRoutes(runtime.spaceVault, setup.spaceName, gates),
                    authorizer = runtime.authorizer,
                    requestLog = { line -> if (setup.debugLogging) Log.i(TAG, line) },
                    gates = gates,
                    closure = closure,
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

        /** Startintent für einen aktiven Space. Ohne Kennung nimmt der Debug-Einstieg den Weg über [serverSetupProvider]. */
        fun startIntent(
            context: Context,
            spaceId: SpaceId? = null,
            port: Int = 0,
        ): Intent =
            Intent(context, SpaceServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
                .apply { spaceId?.let { putExtra(EXTRA_SPACE_ID, it.value) } }

        /** Stoppintent. Beendet den Space durch den Gastgeber und gibt die Sperren frei. */
        fun stopIntent(context: Context): Intent =
            Intent(context, SpaceServerService::class.java).setAction(ACTION_STOP)

        /** Intent für den Wecker am geplanten Ende (ADR 0017). */
        fun expireIntent(context: Context): Intent =
            Intent(context, SpaceServerService::class.java).setAction(ACTION_EXPIRE)

        /** Bestätigt den Wechsel in ein neues WLAN, wenn der Dienst darauf wartet. */
        fun confirmNetworkIntent(context: Context): Intent =
            Intent(context, SpaceServerService::class.java).setAction(ACTION_CONFIRM_NETWORK)

        /**
         * Schließt aktive Spaces ab, wenn kein Dienst mehr läuft. Der Aufrufer ist die App beim Start, vor jedem Start
         * eines Space. Liefert die geschlossenen Spaces.
         */
        suspend fun closeOrphanedSpaces(
            context: Context,
            clock: RunClock = SystemRunClock,
        ): List<SpaceRecord> {
            if (runStateFlow.value !is RunState.Idle) return emptyList()
            return SpaceRunRepository(SpacesDatabase.shared(context), clock).closeWithoutService()
        }

        private fun minutesOf(event: PauseEvent): Long =
            (event.durationMillis + MILLIS_PER_MINUTE / 2) / MILLIS_PER_MINUTE

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MINUTES_PER_HOUR = 60L
    }
}

// Ohne Timeout mit Absicht: Der Typ connectedDevice hat kein Zeitlimit. Die Sperre gilt, bis sie in Stopp oder onDestroy
// ausdrücklich freigegeben wird (siehe SpaceRunner.stop und SpaceServerService.onDestroy).
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
