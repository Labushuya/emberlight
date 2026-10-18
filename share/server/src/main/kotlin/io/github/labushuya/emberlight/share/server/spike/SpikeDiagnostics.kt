package io.github.labushuya.emberlight.share.server.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Diagnose des Spike-Dienstes (ADR 0012, nur Branch): Herzschlag, Bildschirm- und Ladezustand, Doze und Energiesparmodus,
 * WLAN-Verbindung und Sperren. Alles landet in [SpikeDiagLog] unter `filesDir/spike/diag.log`.
 *
 * Bewusst nicht erfasst: SSID und BSSID, Token, Nutzerdaten.
 */
internal class SpikeDiagnostics(
    private val context: Context,
    logFile: File,
    private val scope: CoroutineScope,
    private val locks: () -> SpikeLocks?,
) {
    private val log = SpikeDiagLog(logFile)

    /** Wanduhr des letzten Herzschlags, für `/health`. 0, solange kein Herzschlag lief. */
    @Volatile
    var lastHeartbeatEpochMs: Long = 0L
        private set

    private var heartbeat: Job? = null
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        event("diag-start pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}")
        registerReceiver()
        registerWifi()
        heartbeat =
            scope.launch {
                while (isActive) {
                    beat()
                    delay(HEARTBEAT_MS)
                }
            }
    }

    fun stop() {
        heartbeat?.cancel()
        heartbeat = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        val manager = context.getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        networkCallback = null
        event("diag-stop")
    }

    /** Hängt ein Ereignis an, etwa einen HTTP-Eingang oder das Ende einer Antwort. */
    fun record(text: String) = event(text)

    private fun beat() {
        lastHeartbeatEpochMs = System.currentTimeMillis()
        event("beat ${stateSnapshot()}")
    }

    private fun event(text: String) {
        log.append(
            SpikeDiagLog.formatLine(
                wallMs = System.currentTimeMillis(),
                elapsedMs = SystemClock.elapsedRealtime(),
                uptimeMs = SystemClock.uptimeMillis(),
                event = text,
            ),
        )
    }

    /** Zustand, der bei jedem Herzschlag und bei jedem Broadcast protokolliert wird. */
    private fun stateSnapshot(): String {
        val power = context.getSystemService(PowerManager::class.java)
        val held = locks()
        val light =
            if (Build.VERSION.SDK_INT >= LIGHT_IDLE_SDK) power.isDeviceLightIdleMode else "n/a"
        return "interactive=${power.isInteractive} deviceIdle=${power.isDeviceIdleMode} lightIdle=$light " +
            "powerSave=${power.isPowerSaveMode} batteryExempt=${power.isIgnoringBatteryOptimizations(
                context.packageName,
            )} " +
            "cpuLock=${held?.cpuHeld ?: "none"} wifiLock=${held?.wifiHeld ?: "none"}"
    }

    private fun registerReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= LIGHT_IDLE_SDK) addAction(ACTION_LIGHT_DEVICE_IDLE)
            }
        val created =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    val name = intent.action?.substringAfterLast('.') ?: "unknown"
                    event("broadcast $name ${stateSnapshot()}")
                }
            }
        ContextCompat.registerReceiver(context, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = created
    }

    private fun registerWifi() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    event("wifi onAvailable net=${network.networkHandle}")
                }

                override fun onLost(network: Network) {
                    event("wifi onLost net=${network.networkHandle}")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val notSuspended =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                        } else {
                            null
                        }
                    event("wifi caps net=${network.networkHandle} validated=$validated notSuspended=$notSuspended")
                }

                override fun onBlockedStatusChanged(
                    network: Network,
                    blocked: Boolean,
                ) {
                    event("wifi blocked=$blocked net=${network.networkHandle}")
                }
            }
        manager.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val LIGHT_IDLE_SDK = 33
        const val ACTION_LIGHT_DEVICE_IDLE = "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED"
    }
}
