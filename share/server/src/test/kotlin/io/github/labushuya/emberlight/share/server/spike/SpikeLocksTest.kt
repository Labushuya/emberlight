package io.github.labushuya.emberlight.share.server.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeLocksTest {
    private class FakeLock(
        var failOnAcquire: Boolean = false,
    ) : SpikeLock {
        var acquires = 0
        var releases = 0
        override var isHeld: Boolean = false

        override fun acquire() {
            if (failOnAcquire) throw IllegalStateException("boom")
            acquires++
            isHeld = true
        }

        override fun release() {
            releases++
            isHeld = false
        }
    }

    @Test
    fun acquireHoldsBothLocks() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = SpikeLocks(cpu, wifi)

        locks.acquire()

        assertTrue(locks.cpuHeld)
        assertTrue(locks.wifiHeld)
    }

    @Test
    fun acquireIsIdempotent() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = SpikeLocks(cpu, wifi)

        locks.acquire()
        locks.acquire()

        assertEquals(1, cpu.acquires)
        assertEquals(1, wifi.acquires)
    }

    @Test
    fun releaseFreesBothLocks() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = SpikeLocks(cpu, wifi)
        locks.acquire()

        locks.release()

        assertFalse(locks.cpuHeld)
        assertFalse(locks.wifiHeld)
        assertEquals(1, cpu.releases)
        assertEquals(1, wifi.releases)
    }

    @Test
    fun releaseWithoutAcquireDoesNothing() {
        val cpu = FakeLock()
        val wifi = FakeLock()

        SpikeLocks(cpu, wifi).release()

        assertEquals(0, cpu.releases)
        assertEquals(0, wifi.releases)
    }

    @Test
    fun releaseTwiceReleasesOnce() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = SpikeLocks(cpu, wifi)
        locks.acquire()

        locks.release()
        locks.release()

        assertEquals(1, cpu.releases)
        assertEquals(1, wifi.releases)
    }

    @Test
    fun failedWifiAcquireReleasesCpuLockAgain() {
        val cpu = FakeLock()
        val wifi = FakeLock(failOnAcquire = true)
        val locks = SpikeLocks(cpu, wifi)

        val thrown =
            runCatching { locks.acquire() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(locks.cpuHeld)
        assertFalse(locks.wifiHeld)
        assertEquals(1, cpu.releases)
    }

    @Test
    fun healthJsonReportsLockState() {
        val json =
            spikeHealthJson(
                pid = 7,
                startedAtEpochMs = 1_000L,
                nowEpochMs = 2_000L,
                wakeLockHeld = true,
                wifiLockHeld = false,
                wifiLockMode = SpikeLocks.WIFI_MODE_NAME,
            )

        assertTrue(json.contains("\"wakeLockHeld\":true"))
        assertTrue(json.contains("\"wifiLockHeld\":false"))
        assertTrue(json.contains("\"wifiLockMode\":\"WIFI_MODE_FULL_HIGH_PERF\""))
    }
}
