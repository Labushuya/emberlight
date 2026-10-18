package io.github.labushuya.emberlight.share.server

/** Ein Sperr-Objekt (WakeLock oder WifiLock). Abstrahiert, damit die Freigabe-Logik ohne Android-Laufzeit testbar ist. */
internal interface ServerLock {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/**
 * Hält CPU-Wakelock und WLAN-Lock, solange der Server läuft (ADR 0012).
 *
 * Erwerb und Freigabe sind idempotent. Schlägt der zweite Erwerb fehl, wird der erste sofort wieder freigegeben.
 */
internal class ServerLocks(
    private val cpu: ServerLock,
    private val wifi: ServerLock,
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
}
