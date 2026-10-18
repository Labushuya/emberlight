package io.github.labushuya.emberlight.share.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerLocksTest {
    private class FakeLock(
        var failOnAcquire: Boolean = false,
    ) : ServerLock {
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
        val locks = ServerLocks(FakeLock(), FakeLock())

        locks.acquire()

        assertTrue(locks.cpuHeld)
        assertTrue(locks.wifiHeld)
    }

    @Test
    fun acquireIsIdempotent() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = ServerLocks(cpu, wifi)

        locks.acquire()
        locks.acquire()

        assertEquals(1, cpu.acquires)
        assertEquals(1, wifi.acquires)
    }

    @Test
    fun releaseFreesBothLocks() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = ServerLocks(cpu, wifi)
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

        ServerLocks(cpu, wifi).release()

        assertEquals(0, cpu.releases)
        assertEquals(0, wifi.releases)
    }

    @Test
    fun releaseTwiceReleasesOnce() {
        val cpu = FakeLock()
        val wifi = FakeLock()
        val locks = ServerLocks(cpu, wifi)
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
        val locks = ServerLocks(cpu, wifi)

        val thrown = runCatching { locks.acquire() }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertFalse(locks.cpuHeld)
        assertFalse(locks.wifiHeld)
        assertEquals(1, cpu.releases)
    }
}
