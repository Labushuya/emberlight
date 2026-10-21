package io.github.labushuya.emberlight.feature.space.upload

import io.github.labushuya.emberlight.share.space.IngestResult
import io.github.labushuya.emberlight.share.space.MediaId
import io.github.labushuya.emberlight.share.space.MediaRecord
import io.github.labushuya.emberlight.share.space.ParticipantId
import io.github.labushuya.emberlight.share.space.RejectReason
import io.github.labushuya.emberlight.share.space.SpaceId
import io.github.labushuya.emberlight.share.space.StorageWarning
import io.github.labushuya.emberlight.share.space.location.NotRemovableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gastgeber-Upload im ViewModel (Paket 3.5c): Reihenfolge, Fortschritt, Abbruch, Standort pro Datei, Speicher, Dedupe. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostUploadViewModelTest {
    private val space = SpaceId.parse(io.github.labushuya.emberlight.feature.space.SpaceFixtures.ID_HOCHZEIT)!!
    private lateinit var port: FakeHostUploadPort
    private lateinit var model: HostUploadViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        port = FakeHostUploadPort()
        model = HostUploadViewModel(port)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stored(warning: Boolean = false): PickedUploadOutcome.Ingested =
        PickedUploadOutcome.Ingested(IngestResult.Created(record(), if (warning) StorageWarning.STORAGE_80 else null))

    private fun duplicate(): PickedUploadOutcome.Ingested =
        PickedUploadOutcome.Ingested(IngestResult.Duplicate(record(), null))

    private fun rejected(reason: RejectReason): PickedUploadOutcome.Ingested =
        PickedUploadOutcome.Ingested(IngestResult.Rejected(reason))

    private fun record(): MediaRecord =
        MediaRecord(
            id = MediaId.generate(),
            mime = "image/jpeg",
            sizeBytes = 10L,
            sha256 = "a".repeat(64),
            originalName = "bild.jpg",
            uploaderId = ParticipantId.generate(),
            createdAt = 1L,
        )

    @Test
    fun filesAreStoredOneAfterTheOtherInTheOrderPicked() {
        port.file("u1", "eins.jpg", 10)
        port.file("u2", "zwei.jpg", 20)
        port.answer("u1", stored())
        port.answer("u2", stored(warning = true))

        model.enqueue(space, listOf("u1", "u2"))

        val state = model.state.value
        assertEquals(listOf("eins.jpg", "zwei.jpg"), state.items.map { it.name })
        assertEquals(listOf(UploadStatus.Stored(false), UploadStatus.Stored(true)), state.items.map { it.status })
        assertFalse(state.running)
        assertTrue(state.storageWarning)
        assertEquals(listOf("u1" to false, "u2" to false), port.calls)
    }

    @Test
    fun progressIsReportedWhileAFileIsUploading() {
        port.gate = CompletableDeferred()
        port.answer("u1", stored())
        port.progress = listOf(UploadProgress(40, 100))

        model.enqueue(space, listOf("u1"))

        val running =
            model.state.value.items
                .single()
        assertEquals(UploadStatus.Uploading, running.status)
        assertEquals(UploadProgress(40, 100), running.progress)
        assertTrue(model.state.value.running)

        port.gate!!.complete(Unit)

        assertEquals(
            UploadStatus.Stored(false),
            model.state.value.items
                .single()
                .status,
        )
        assertNull(
            model.state.value.items
                .single()
                .progress,
        )
    }

    @Test
    fun aFileWithoutAKnownSizeKeepsItsProgressUndetermined() {
        port.gate = CompletableDeferred()
        port.answer("u1", stored())
        port.progress = listOf(UploadProgress(4_096, null))

        model.enqueue(space, listOf("u1"))

        assertNull(
            model.state.value.items
                .single()
                .progress
                ?.totalBytes,
        )
        port.gate!!.complete(Unit)
    }

    @Test
    fun cancelStopsTheFileInFlightAndTheWaitingOnes() {
        port.gate = CompletableDeferred()
        port.answer("u1", stored())
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1", "u2"))
        model.cancel()

        val items = model.state.value.items
        assertEquals(
            listOf<UploadStatus>(
                UploadStatus.Stopped(StopReason.CANCELLED),
                UploadStatus.Stopped(StopReason.CANCELLED),
            ),
            items.map {
                it.status
            },
        )
        assertFalse(model.state.value.running)
        assertEquals(listOf("u1" to false), port.calls)
    }

    @Test
    fun aLocationThatCannotBeRemovedAsksPerFileAndUploadsWithConsent() {
        val refused = rejected(RejectReason.LocationNotRemovable(NotRemovableReason.STRUCTURE))
        port.file("u1", "reise.jpg", 10)
        port.file("u2", "park.jpg", 10)
        port.answer("u1", refused)
        port.answer("u1", stored())
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1", "u2"))
        // Die Rückfrage gilt der ersten Datei. Die zweite wartet, bis die erste entschieden ist.
        assertEquals("reise.jpg", model.state.value.locationQuestion)
        assertEquals(
            UploadStatus.Waiting,
            model.state.value.items[1]
                .status,
        )

        model.answerLocation(keep = true)

        assertNull(model.state.value.locationQuestion)
        assertEquals(
            listOf(UploadStatus.Stored(false), UploadStatus.Stored(false)),
            model.state.value.items
                .map { it.status },
        )
        assertEquals(listOf("u1" to false, "u1" to true, "u2" to false), port.calls)
    }

    @Test
    fun declinedLocationSkipsOnlyThatFile() {
        val refused = rejected(RejectReason.LocationNotRemovable(NotRemovableReason.STRUCTURE))
        port.answer("u1", refused)
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1", "u2"))
        model.answerLocation(keep = false)

        assertEquals(
            listOf<UploadStatus>(
                UploadStatus.Skipped,
                UploadStatus.Stored(false),
            ),
            model.state.value.items.map {
                it.status
            },
        )
        assertEquals(listOf("u1" to false, "u2" to false), port.calls)
    }

    @Test
    fun aDuplicateIsReportedAndNotStoredAgain() {
        port.answer("u1", duplicate())

        model.enqueue(space, listOf("u1"))

        assertEquals(
            UploadStatus.Duplicate,
            model.state.value.items
                .single()
                .status,
        )
    }

    @Test
    fun aFullStorageStopsTheRestOfTheBatch() {
        port.answer("u1", stored())
        port.answer("u2", rejected(RejectReason.StorageFull))

        model.enqueue(space, listOf("u1", "u2", "u3"))

        assertEquals(
            listOf<UploadStatus>(
                UploadStatus.Stored(false),
                UploadStatus.Rejected(RejectReason.StorageFull),
                UploadStatus.Stopped(StopReason.STORAGE_FULL),
            ),
            model.state.value.items
                .map { it.status },
        )
        assertEquals(listOf("u1" to false, "u2" to false), port.calls)
    }

    @Test
    fun aRejectedFormatOnlyFailsItsFile() {
        port.answer("u1", rejected(RejectReason.UnsupportedType))
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1", "u2"))

        assertEquals(
            listOf<UploadStatus>(UploadStatus.Rejected(RejectReason.UnsupportedType), UploadStatus.Stored(false)),
            model.state.value.items
                .map { it.status },
        )
    }

    @Test
    fun anUnreadableFileIsReportedAndTheRestContinues() {
        port.answer("u1", PickedUploadOutcome.Unreadable)
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1", "u2"))

        assertEquals(
            listOf<UploadStatus>(
                UploadStatus.Unreadable,
                UploadStatus.Stored(false),
            ),
            model.state.value.items.map {
                it.status
            },
        )
    }

    @Test
    fun nothingIsUploadedWhileTheSpaceIsNotRunning() {
        port.running = false

        model.enqueue(space, listOf("u1", "u2"))

        assertEquals(
            listOf<UploadStatus>(
                UploadStatus.Stopped(StopReason.NOT_RUNNING),
                UploadStatus.Stopped(StopReason.NOT_RUNNING),
            ),
            model.state.value.items
                .map { it.status },
        )
        assertTrue(port.calls.isEmpty())
        assertFalse(model.state.value.running)
    }

    @Test
    fun aBatchAddedWhileRunningJoinsTheCurrentOne() {
        port.gate = CompletableDeferred()
        port.answer("u1", stored())
        port.answer("u2", stored())

        model.enqueue(space, listOf("u1"))
        model.enqueue(space, listOf("u2"))
        port.gate!!.complete(Unit)

        assertEquals(
            listOf("u1", "u2"),
            model.state.value.items
                .map { it.uri },
        )
        assertEquals(
            listOf(UploadStatus.Stored(false), UploadStatus.Stored(false)),
            model.state.value.items
                .map { it.status },
        )
    }

    @Test
    fun emptySelectionChangesNothing() {
        model.enqueue(space, emptyList())

        assertTrue(
            model.state.value.items
                .isEmpty(),
        )
        assertFalse(model.state.value.running)
    }
}
