package io.github.labushuya.emberlight.core.media.store

import app.cash.turbine.test
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.log.MediaLogger
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.core.media.permission.MediaPermissionChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository mit Fake-Gateway auf dem JVM. Die Abfragen laufen auf einem Test-Dispatcher mit virtueller Zeit,
 * damit die Entprellung (400 ms) exakt prüfbar ist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreMediaRepositoryTest {
    @Test
    fun startsLoadingThenShowsLibrary() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), videos = listOf(video(2, 2_000)))
            val repo = repository(gateway, granted = ALL_GRANTED)

            assertEquals(MediaLibraryState.Loading, repo.library.value)
            advanceUntilIdle()

            val library = readyLibrary(repo)
            assertEquals(listOf(2L, 1L), library.items.map { it.id })
            assertEquals(MediaAccess.FULL, library.mediaAccess)
        }

    @Test
    fun turbineSeesLoadingThenReady() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)))
            val repo = repository(gateway, granted = ALL_GRANTED)

            repo.library.test {
                assertEquals(MediaLibraryState.Loading, awaitItem())
                advanceUntilIdle()
                val ready = awaitItem() as MediaLibraryState.Ready
                assertEquals(listOf(1L), ready.library.items.map { it.id })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun noPermissionMeansNoneAndNoQuery() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)))
            val repo = repository(gateway, granted = emptySet())

            advanceUntilIdle()

            val library = readyLibrary(repo)
            assertEquals(MediaAccess.NONE, library.mediaAccess)
            assertEquals(emptyList<MediaItem>(), library.items)
            assertEquals(0, gateway.queryCount)
        }

    @Test
    fun partialAccessQueriesAndReportsPartial() =
        runTest {
            val gateway = FakeGateway(sdk = 34, images = listOf(image(1, 1_000)))
            val repo = repository(gateway, granted = setOf(VISUAL))

            advanceUntilIdle()

            val library = readyLibrary(repo)
            assertEquals(MediaAccess.PARTIAL, library.mediaAccess)
            assertEquals(listOf(1L), library.items.map { it.id })
        }

    @Test
    fun securityExceptionGivesEmptyListWithNone() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), queryError = SecurityException("revoked"))
            val logger = RecordingLogger()
            val repo = repository(gateway, granted = ALL_GRANTED, logger = logger)

            advanceUntilIdle()

            val library = readyLibrary(repo)
            assertEquals(MediaAccess.NONE, library.mediaAccess)
            assertEquals(emptyList<MediaItem>(), library.items)
            // Ein Entzug der Berechtigung ist kein unerwarteter Fehler und landet nicht im Log.
            assertEquals(0, logger.messages.size)
        }

    @Test
    fun changeEventsAreDebouncedIntoOneRound() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), currentGeneration = 1L)
            val repo = repository(gateway, granted = ALL_GRANTED)
            advanceUntilIdle()
            val before = gateway.queryCount

            gateway.images = listOf(image(1, 1_000), image(2, 2_000))
            gateway.currentGeneration = 2L
            gateway.emitChange()
            advanceTimeBy(100)
            gateway.emitChange()
            advanceTimeBy(100)
            gateway.emitChange()

            advanceTimeBy(399)
            assertEquals(before, gateway.queryCount)
            advanceTimeBy(1)
            advanceUntilIdle()

            // Bilder und Videos in einer Runde: zwei Abfragen.
            assertEquals(before + 2, gateway.queryCount)
            assertEquals(listOf(2L, 1L), readyLibrary(repo).items.map { it.id })
        }

    @Test
    fun unchangedGenerationSkipsQuery() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), currentGeneration = 5L)
            repository(gateway, granted = ALL_GRANTED)
            advanceUntilIdle()
            val before = gateway.queryCount

            gateway.emitChange()
            advanceUntilIdle()

            assertEquals(before, gateway.queryCount)
        }

    @Test
    fun missingGenerationQueriesOnEveryChange() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), currentGeneration = null)
            repository(gateway, granted = ALL_GRANTED)
            advanceUntilIdle()
            val before = gateway.queryCount

            gateway.emitChange()
            advanceUntilIdle()

            assertEquals(before + 2, gateway.queryCount)
        }

    @Test
    fun refreshForcesQueryEvenWithUnchangedGeneration() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), currentGeneration = 5L)
            val repo = repository(gateway, granted = ALL_GRANTED)
            advanceUntilIdle()
            val before = gateway.queryCount

            gateway.images = listOf(image(1, 1_000), image(3, 3_000))
            repo.refresh()
            advanceUntilIdle()

            assertEquals(before + 2, gateway.queryCount)
            assertEquals(listOf(3L, 1L), readyLibrary(repo).items.map { it.id })
        }

    @Test
    fun refreshAfterPermissionGrantPicksUpAccess() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)))
            val permissions = FakePermissions(granted = mutableSetOf())
            val repo = repository(gateway, permissions = permissions)
            advanceUntilIdle()
            assertEquals(MediaAccess.NONE, readyLibrary(repo).mediaAccess)

            permissions.granted += ALL_GRANTED
            repo.refresh()
            advanceUntilIdle()

            assertEquals(MediaAccess.FULL, readyLibrary(repo).mediaAccess)
        }

    @Test
    fun unexpectedErrorKeepsLastStateAndLogs() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)))
            val logger = RecordingLogger()
            val repo = repository(gateway, granted = ALL_GRANTED, logger = logger)
            advanceUntilIdle()
            val good = repo.library.value

            gateway.queryError = IllegalStateException("database locked")
            repo.refresh()
            advanceUntilIdle()

            assertEquals(good, repo.library.value)
            assertEquals(1, logger.messages.size)

            // Die Sammel-Coroutine lebt weiter und verarbeitet spätere Änderungen.
            gateway.queryError = null
            gateway.images = listOf(image(9, 9_000))
            gateway.currentGeneration = 2L
            gateway.emitChange()
            advanceUntilIdle()
            assertEquals(listOf(9L), readyLibrary(repo).items.map { it.id })
        }

    @Test
    fun observerFailureIsLoggedAndRefreshStillWorks() =
        runTest {
            val gateway = FakeGateway(images = listOf(image(1, 1_000)), changesFail = true)
            val logger = RecordingLogger()
            val repo = repository(gateway, granted = ALL_GRANTED, logger = logger)
            advanceUntilIdle()
            assertEquals(1, logger.messages.size)

            gateway.images = listOf(image(4, 4_000))
            repo.refresh()
            advanceUntilIdle()

            assertTrue(repo.library.value is MediaLibraryState.Ready)
            assertEquals(listOf(4L), readyLibrary(repo).items.map { it.id })
        }

    @Test
    fun missingTypeIsNotQueriedAndIsReported() =
        runTest {
            val gateway = FakeGateway(sdk = 33, images = listOf(image(1, 1_000)), videos = listOf(video(2, 2_000)))
            val repo = repository(gateway, granted = setOf(IMAGES))

            advanceUntilIdle()

            val library = readyLibrary(repo)
            assertEquals(MediaAccess.FULL, library.mediaAccess)
            assertEquals(setOf(MediaType.VIDEO), library.missingTypes)
            assertEquals(listOf(1L), library.items.map { it.id })
            // Ohne Berechtigung für Videos wird nicht abgefragt. Sonst würde die ganze Bibliothek auf NONE fallen.
            assertEquals(listOf(MediaType.IMAGE), gateway.queriedTypes)
        }

    @Test
    fun bothTypesGrantedReportsNoMissingType() =
        runTest {
            val gateway = FakeGateway(sdk = 33, images = listOf(image(1, 1_000)), videos = listOf(video(2, 2_000)))
            val repo = repository(gateway, granted = ALL_GRANTED)

            advanceUntilIdle()

            assertEquals(emptySet<MediaType>(), readyLibrary(repo).missingTypes)
        }

    private fun TestScope.repository(
        gateway: FakeGateway,
        granted: Set<String>,
        logger: MediaLogger = RecordingLogger(),
    ): MediaStoreMediaRepository = repository(gateway, FakePermissions(granted.toMutableSet()), logger)

    private fun TestScope.repository(
        gateway: FakeGateway,
        permissions: FakePermissions,
        logger: MediaLogger = RecordingLogger(),
    ): MediaStoreMediaRepository =
        MediaStoreMediaRepository(
            gateway = gateway,
            permissions = permissions,
            logger = logger,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun readyLibrary(repo: MediaStoreMediaRepository) = (repo.library.value as MediaLibraryState.Ready).library

    private class FakeGateway(
        var sdk: Int = 37,
        var images: List<MediaItem> = emptyList(),
        var videos: List<MediaItem> = emptyList(),
        var queryError: RuntimeException? = null,
        var currentGeneration: Long? = 1L,
        private val changesFail: Boolean = false,
    ) : MediaStoreGateway {
        val queriedTypes = mutableListOf<MediaType>()
        var queryCount = 0
            private set
        private val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

        override val sdkInt: Int get() = sdk

        override fun query(type: MediaType): List<MediaItem> {
            queryCount++
            queriedTypes += type
            queryError?.let { throw it }
            return if (type == MediaType.IMAGE) images else videos
        }

        override fun generation(): Long? = currentGeneration

        override fun changes(): Flow<Unit> =
            if (changesFail) {
                flow { throw IllegalStateException("observer registration failed") }
            } else {
                signals
            }

        fun emitChange() {
            signals.tryEmit(Unit)
        }
    }

    private class FakePermissions(
        val granted: MutableSet<String>,
    ) : MediaPermissionChecker {
        override fun isGranted(permission: String): Boolean = permission in granted
    }

    private class RecordingLogger : MediaLogger {
        val messages = mutableListOf<String>()

        override fun warn(
            message: String,
            throwable: Throwable,
        ) {
            messages += message
        }
    }

    private companion object {
        const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val VISUAL = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        val ALL_GRANTED = setOf(IMAGES, VIDEO)

        fun image(
            id: Long,
            timestamp: Long,
        ) = item(id, MediaType.IMAGE, timestamp)

        fun video(
            id: Long,
            timestamp: Long,
        ) = item(id, MediaType.VIDEO, timestamp)

        fun item(
            id: Long,
            type: MediaType,
            timestamp: Long,
        ) = MediaItem(
            id = id,
            uri = "content://media/external/${type.name.lowercase()}/media/$id",
            type = type,
            displayName = null,
            mimeType = null,
            timestamp = timestamp,
            timestampSource = TimestampSource.DATE_TAKEN,
            width = null,
            height = null,
            orientation = null,
            durationMs = null,
            sizeBytes = null,
            bucketId = null,
            bucketName = null,
            relativePath = null,
            volumeName = null,
        )
    }
}
