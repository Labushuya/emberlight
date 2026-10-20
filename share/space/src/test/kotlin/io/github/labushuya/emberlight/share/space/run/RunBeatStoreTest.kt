package io.github.labushuya.emberlight.share.space.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.labushuya.emberlight.share.space.SpaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Speicher des letzten Herzschlags (ADR 0017, Neustart). Der Neustart nach einem Prozess-Tod liest genau diesen Stand, also
 * muss er den Lauf unverändert zurückgeben und nach dem Beenden leer sein.
 */
@RunWith(RobolectricTestRunner::class)
class RunBeatStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startEmpty() {
        PrefsRunBeatStore(context).clear()
    }

    @Test
    fun nothingIsStoredBeforeTheFirstBeat() {
        assertNull(PrefsRunBeatStore(context).load())
    }

    @Test
    fun aSavedBeatComesBackUnchangedEvenFromAFreshStore() {
        val beat = beat(spaceId = SpaceId.generate(), lastWall = 3_000L)

        PrefsRunBeatStore(context).save(beat)

        assertEquals(beat, PrefsRunBeatStore(context).load())
    }

    @Test
    fun aLaterBeatReplacesTheEarlierOne() {
        val store = PrefsRunBeatStore(context)
        store.save(beat(spaceId = SpaceId.generate(), lastWall = 1_000L))
        val later = beat(spaceId = SpaceId.generate(), lastWall = 9_000L)

        store.save(later)

        assertEquals(later, store.load())
    }

    @Test
    fun clearingForgetsTheRun() {
        val store = PrefsRunBeatStore(context)
        store.save(beat(spaceId = SpaceId.generate(), lastWall = 3_000L))

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun theMemoryStoreKeepsOnlyTheLastBeatAndClears() {
        val store = MemoryRunBeatStore()
        assertNull(store.load())
        val beat = beat(spaceId = SpaceId.generate(), lastWall = 5_000L)

        store.save(beat)
        assertEquals(beat, store.load())

        store.clear()
        assertNull(store.load())
    }

    private fun beat(
        spaceId: SpaceId,
        lastWall: Long,
    ): RunBeat =
        RunBeat(
            spaceId = spaceId,
            startedAtMillis = 1_000L,
            startedElapsedMillis = 2_000L,
            lastWallMillis = lastWall,
            lastElapsedMillis = lastWall + 1_000L,
            port = 8787,
        )
}
