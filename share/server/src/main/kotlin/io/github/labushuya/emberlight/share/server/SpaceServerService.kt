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
import io.github.labushuya.emberlight.share.space.participants.AndroidKeyStoreInviteSecretCipher
import io.github.labushuya.emberlight.share.space.participants.InviteSecretCipher
import io.github.labushuya.emberlight.share.space.run.ActiveSpan
import io.github.labushuya.emberlight.share.space.run.PauseEvent
import io.github.labushuya.emberlight.share.space.run.PrefsRunBeatStore
import io.github.labushuya.emberlight.share.space.run.RunBeatStore
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
 * Prozess-Tod neu (`START_STICKY`, ADR 0017, Neustart). Ein Prozess-Tod oder ein Abbruch durch das System beendet den
 * Space nie. Der Neustart setzt ihn fort, wenn die Akku-Ausnahme besteht. Sonst bleibt er aktiv und unterbrochen, bis
 * der Gastgeber ihn in der App fortsetzt.
 */
class SpaceServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = ServiceHost()
    private val beats: RunBeatStore by lazy { beatStoreFactory(this) }
    private val runner: SpaceRunner by lazy { SpaceRunner(scope, SystemRunClock, host, runStateFlow, beats) }
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
        // START_STICKY: Nach einem Prozess-Tod startet das System den Dienst mit einem Intent ohne Auftrag neu.
        if (intent == null) {
            restartAfterProcessDeath(startId)
            return START_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                runner.submit(RunCommand.Stop(StopReason.USER))
                return START_STICKY
            }

            ACTION_EXPIRE -> {
                runner.submit(RunCommand.Expire)
                return START_STICKY
            }

            ACTION_CONFIRM_NETWORK -> {
                runner.submit(RunCommand.ConfirmNetwork)
                return START_STICKY
            }

            ACTION_INVITE_SETTLED -> {
                runner.submit(RunCommand.InviteSettled)
                // Ohne laufenden Space gibt es nichts zu quittieren. Der Dienst endet dann gleich wieder.
                if (runState.value is RunState.Idle) stopSelf(startId)
                return START_STICKY
            }
        }
        // Vordergrund zuerst: Android verlangt den Aufruf kurz nach startForegroundService. Auch unbekannte Aufrufe
        // erfüllen diese Pflicht, bevor der Dienst wieder endet.
        if (!ensureForeground()) {
            Log.e(TAG, "Vordergrund-Start verweigert, der Dienst endet.")
            stopSelf(startId)
            return START_STICKY
        }
        if (intent.action != ACTION_START) {
            // Unbekannter Aufruf ohne Auftrag: Der Dienst endet, wenn kein Space läuft.
            if (runState.value is RunState.Idle) stopSelf(startId)
            return START_STICKY
        }
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID)?.let(::SpaceId) ?: SpaceId.generate()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        runner.submit(RunCommand.Start(spaceId, port, resume))
        return START_STICKY
    }

    /**
     * Neustart nach einem Prozess-Tod. Ohne gespeicherten Lauf gibt es nichts fortzusetzen: Der Dienst endet, ohne in den
     * Vordergrund zu gehen. Sonst geht er in den Vordergrund und entscheidet der Verarbeiter. Scheitert der Vordergrund-
     * Start, endet der Dienst. Der Space bleibt `ACTIVE` und unterbrochen (ADR 0017).
     */
    private fun restartAfterProcessDeath(startId: Int) {
        if (beats.load() == null) {
            stopSelf(startId)
            return
        }
        if (!ensureForeground()) {
            Log.w(TAG, "Neustart ohne Vordergrund: der Space bleibt unterbrochen.")
            stopSelf(startId)
            return
        }
        runner.submit(RunCommand.Restart)
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

        /** Gemerkte Benachrichtigungen zum Ende. Eigene Einstellungsdatei, damit nichts doppelt kommt (ADR 0017). */
        private val endNotices: EndNoticeStore by lazy {
            PrefsEndNoticeStore(this@SpaceServerService.getSharedPreferences(END_NOTICE_PREFS, MODE_PRIVATE))
        }

        override suspend fun setupFor(
            spaceId: SpaceId,
            resuming: Boolean,
        ): ServerSetup? {
            if (!localNetworkAllowed()) {
                Log.w(TAG, "ACCESS_LOCAL_NETWORK fehlt auf API ${Build.VERSION.SDK_INT}, der Start ist abgelehnt.")
                // Ein frischer Start geht zurück auf Entwurf. Ein fortgesetzter Space bleibt aktiv und unterbrochen.
                if (!resuming) runs.abortStart(spaceId)
                return null
            }
            serverSetupProvider()?.let { return it }
            val record = runs.findActive()?.takeIf { it.id == spaceId } ?: return null
            return record.toServerSetup(port = runs.portOf(spaceId))
        }

        override suspend fun activeSpan(): ActiveSpan? =
            runs.findActive()?.let { ActiveSpan(it.id, it.startedAt, it.expiresAt) }

        override suspend fun savePort(
            spaceId: SpaceId,
            port: Int,
        ) {
            runs.savePort(spaceId, port)
        }

        override fun batteryExempt(): Boolean = batteryExemptFactory(this@SpaceServerService)

        override suspend fun openServer(
            setup: ServerSetup,
            closure: SpaceClosure,
        ): OpenedServer {
            val runtime = SpaceRuntime.open(applicationContext, setup, inviteCipherFactory())
            ActiveSpaces.publish(runtime.access)
            return OpenedServer(
                serverFactory(runtime, setup, closure),
                // Die Vault bleibt offen: Der Vorschau-Worker und ein Upload aus der App laufen weiter (Paket 3.5c).
                release = { ActiveSpaces.clear(setup.spaceId) },
                inviteRenewed = runtime.access.inviteRenewed,
            )
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
            // Name und Aufbewahrung stehen noch in der Tabelle, bevor das Ende sie abschließt.
            val info = runs.endingInfoOf(spaceId)
            runs.end(spaceId, endedAtMillis)
            if (info != null && !endNotices.ended(spaceId.value)) {
                endNotices.markEnded(spaceId.value)
                val date =
                    EndNotices.keptUntilDate(
                        EndNotices.keptUntilMillis(endedAtMillis, info.retentionDays),
                    )
                postAlert(
                    text = getString(R.string.space_server_ended_text, info.name, date),
                    title = getString(R.string.space_server_ended_title),
                    notificationId = END_ENDED_NOTIFICATION_ID,
                )
            }
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
                RunEvent.Interrupted -> {
                    postAlert(getString(R.string.space_server_interrupted))
                }

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
            // Vorwarnung aus dem Herzschlag (alle 30 s). Gemerkt, damit ein Neustart sie nicht doppelt sendet.
            val spaceId = state.spaceId.value
            if (EndNotices.warningDue(System.currentTimeMillis(), state.expiresAtMillis, endNotices.warned(spaceId))) {
                endNotices.markWarned(spaceId)
                postAlert(
                    text = getString(R.string.space_server_warning_text, state.spaceName),
                    title = getString(R.string.space_server_warning_title),
                    notificationId = END_WARNING_NOTIFICATION_ID,
                )
            }
        }

        override fun onIdle() {
            // Nur die Dauermeldung entfernen und den Dienst beenden. Ein neuerer Auftrag verhindert das Beenden.
            ServiceCompat.stopForeground(this@SpaceServerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }
    }

    /** Einrichtung eines gespeicherten, aktiven Space für den Server. */
    private fun SpaceRecord.toServerSetup(port: Int?): ServerSetup =
        ServerSetup(
            spaceId = id,
            spaceName = settings.name,
            colorIndex = settings.colorIndex,
            limits = SpaceLimits(storageLimitBytes = settings.storageLimitBytes),
            startedAtMillis = startedAt,
            expiresAtMillis = expiresAt,
            persisted = true,
            port = port,
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
        title: String = getString(R.string.space_server_running_title),
        notificationId: Int = ALERT_NOTIFICATION_ID,
    ) {
        if (!canPostNotifications()) return
        val builder =
            NotificationCompat
                .Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
        launchPendingIntent(route ?: ROUTE_OPEN_SPACE, REQUEST_OPEN_SPACE)?.let(builder::setContentIntent)
        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
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
        const val ACTION_INVITE_SETTLED = "io.github.labushuya.emberlight.share.server.INVITE_SETTLED"
        const val EXTRA_PORT = "io.github.labushuya.emberlight.share.server.PORT"
        const val EXTRA_SPACE_ID = "io.github.labushuya.emberlight.share.server.SPACE_ID"
        const val EXTRA_RESUME = "io.github.labushuya.emberlight.share.server.RESUME"

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
        private const val END_WARNING_NOTIFICATION_ID = 4713
        private const val END_ENDED_NOTIFICATION_ID = 4714
        private const val END_NOTICE_PREFS = "emberlight.end-notices"
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

        // Verschlüsselung des Einladungsgeheimnisses (ADR 0018). Tests setzen ein Fake, weil Robolectric keinen Keystore hat.
        internal var inviteCipherFactory: () -> InviteSecretCipher = { AndroidKeyStoreInviteSecretCipher() }

        /** Test-Nähte. Produktion nutzt die Standardwerte. */
        internal var serverFactory: (SpaceRuntime, ServerSetup, SpaceClosure) -> LocalServer =
            { runtime, setup, closure ->
                val gates = ServerGates()
                KtorLocalServer(
                    routes = serverRoutes(runtime.access, runtime.guestAssets, gates),
                    authorizer = runtime.access.live,
                    signedMedia = runtime.access.mediaAccess,
                    requestLog = { line -> if (setup.debugLogging) Log.i(TAG, line) },
                    gates = gates,
                    closure = closure,
                )
            }
        internal var networkFactory: (Context) -> WifiNetworkProvider = { context ->
            ConnectivityWifiNetworkProvider(context)
        }
        internal var locksFactory: (SpaceServerService) -> ServerLocks = { service -> service.createServerLocks() }

        /** Speicher für den letzten Herzschlag. Tests setzen einen Speicher im Arbeitsspeicher. */
        internal var beatStoreFactory: (Context) -> RunBeatStore = { context -> PrefsRunBeatStore(context) }

        /** Ausnahme von der Akku-Optimierung. Tests setzen einen festen Wert. */
        internal var batteryExemptFactory: (Context) -> Boolean = { context ->
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
        internal var foregroundStarter: ForegroundStarter =
            ForegroundStarter { service, notification, type ->
                ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, type)
            }

        /**
         * Startintent für einen aktiven Space. Ohne Kennung nimmt der Debug-Einstieg den Weg über [serverSetupProvider].
         * Mit [resume] setzt er einen unterbrochenen, aktiven Space fort (Schaltfläche „Fortsetzen“).
         */
        fun startIntent(
            context: Context,
            spaceId: SpaceId? = null,
            port: Int = 0,
            resume: Boolean = false,
        ): Intent =
            Intent(context, SpaceServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_RESUME, resume)
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

        /** Quittiert den Hinweis zur erneuerten Einladung. Kein Start, kein Vordergrund nötig. */
        fun inviteSettledIntent(context: Context): Intent =
            Intent(context, SpaceServerService::class.java).setAction(ACTION_INVITE_SETTLED)

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
