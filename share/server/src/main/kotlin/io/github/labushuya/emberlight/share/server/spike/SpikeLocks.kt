package io.github.labushuya.emberlight.share.server.spike

/** Ein Sperr-Objekt (WakeLock oder WifiLock). Abstrahiert, damit die Freigabe-Logik ohne Android-Laufzeit testbar ist. */
internal interface SpikeLock {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/**
 * Hält während eines laufenden Spike-Servers den CPU-Wakelock und den WLAN-Lock (ADR 0012, Spike).
 *
 * Erwerb und Freigabe sind idempotent. Schlägt der zweite Erwerb fehl, wird der erste sofort wieder freigegeben.
 * Der WLAN-Lock nutzt [WIFI_MODE_NAME], weil `WIFI_MODE_FULL_LOW_LATENCY` laut Doku nur bei eingeschaltetem Bildschirm
 * und im Vordergrund greift.
 */
internal class SpikeLocks(
    private val cpu: SpikeLock,
    private val wifi: SpikeLock,
) {
    val cpuHeld: Boolean get() = cpu.isHeld
    val wifiHeld: Boolean get() = wifi.isHeld

    @Synchronized
    fun acquire() {
        if (!cpu.isHeld) cpu.acquire()
        if (!wifi.isHeld) {
            try {
                wifi.acquire()
            } catch (failure: Throwable) {
                if (cpu.isHeld) cpu.release()
                throw failure
            }
        }
    }

    @Synchronized
    fun release() {
        if (wifi.isHeld) wifi.release()
        if (cpu.isHeld) cpu.release()
    }

    companion object {
        /** Name des WLAN-Modus für `/health`. Entspricht `WifiManager.WIFI_MODE_FULL_HIGH_PERF`. */
        const val WIFI_MODE_NAME = "WIFI_MODE_FULL_HIGH_PERF"
    }
}
