package io.github.labushuya.emberlight.testing

import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.core.media.di.MediaRepositoryModule
import io.github.labushuya.emberlight.core.media.model.Album
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaLibrary
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.core.media.permission.MediaAccess
import io.github.labushuya.emberlight.core.media.thumbnail.MediaThumbnail
import io.github.labushuya.emberlight.feature.gallery.di.DefaultDispatcher
import io.github.labushuya.emberlight.feature.gallery.di.TimelineModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Heute ist der 16. Oktober 2026 in Berlin. Alle Zeitpunkte liegen in Berlin und UTC am selben Tag. */
internal val TEST_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

/**
 * Medienindex mit festen Daten. Der Index ersetzt das echte MediaStore-Repository in allen Hilt-Tests.
 * Die Zustände setzt der Test über [show].
 */
@Singleton
class FakeMediaRepository
    @Inject
    constructor() : MediaRepository {
        private val state = MutableStateFlow<MediaLibraryState>(MediaLibraryState.Ready(sampleLibrary()))

        override val library: StateFlow<MediaLibraryState> = state

        override fun refresh() {
            // Feste Daten: nichts zu lesen.
        }

        fun show(library: MediaLibrary) {
            state.value = MediaLibraryState.Ready(library)
        }

        companion object {
            /** Ordner der Beispieldaten: Kamera, Screenshots und Downloads. Der Eintrag ohne Ordner ist „Sonstige“. */
            const val CAMERA_BUCKET = 1L
            const val SCREENSHOT_BUCKET = 2L
            const val DOWNLOAD_BUCKET = 3L

            fun sampleLibrary(
                items: List<MediaItem> = sampleItems(),
                access: MediaAccess = MediaAccess.FULL,
                missingTypes: Set<MediaType> = emptySet(),
            ): MediaLibrary =
                MediaLibrary(items = items, albums = albumsOf(items), mediaAccess = access, missingTypes = missingTypes)

            /** Absteigend sortiert: Heute, Gestern, Oktober, Juli (mit Stunden-Video), Juni 2025. Mit Ordnern wie in den Feature-Tests. */
            fun sampleItems(): List<MediaItem> =
                listOf(
                    item(9, "2026-10-16T09:30", MediaType.VIDEO, durationMs = 42_000L),
                    item(8, "2026-10-16T08:15"),
                    item(7, "2026-10-15T19:40", bucketId = SCREENSHOT_BUCKET, bucketName = "Screenshots"),
                    item(6, "2026-10-15T10:02"),
                    item(5, "2026-10-12T17:55"),
                    item(
                        4,
                        "2026-07-02T21:10",
                        MediaType.VIDEO,
                        durationMs = 3_723_000L,
                        bucketId = null,
                        bucketName = null,
                    ),
                    item(3, "2025-06-03T11:00", bucketId = DOWNLOAD_BUCKET, bucketName = "Downloads"),
                    item(2, "2025-06-03T10:30", bucketId = DOWNLOAD_BUCKET, bucketName = "Downloads"),
                )

            /**
             * Alben wie der Index sie bildet: je Ordner das neueste Element als Cover, die Anzahl, der jüngste Zeitpunkt.
             * Sortiert neueste zuerst, bei Gleichstand der Ordner ohne Namen zuletzt.
             */
            fun albumsOf(items: List<MediaItem>): List<Album> {
                val byBucket = LinkedHashMap<Long?, MutableList<MediaItem>>()
                items.sortedByDescending { it.timestamp }.forEach {
                    byBucket
                        .getOrPut(
                            it.bucketId,
                        ) { mutableListOf() }
                        .add(it)
                }
                return byBucket
                    .map { (bucketId, list) ->
                        Album(
                            bucketId = bucketId,
                            name = list.first().bucketName.orEmpty(),
                            cover = list.first(),
                            itemCount = list.size,
                            latestTimestamp = list.first().timestamp,
                        )
                    }.sortedWith(
                        compareByDescending<Album> { it.latestTimestamp }
                            .thenBy(nullsLast()) { it.bucketId },
                    )
            }

            private fun item(
                id: Long,
                localDateTime: String,
                type: MediaType = MediaType.IMAGE,
                durationMs: Long? = null,
                bucketId: Long? = CAMERA_BUCKET,
                bucketName: String? = "Camera",
            ): MediaItem =
                MediaItem(
                    id = id,
                    uri = "content://media/external/${type.name.lowercase()}/media/$id",
                    type = type,
                    displayName = "file$id",
                    mimeType = null,
                    timestamp =
                        LocalDateTime
                            .parse(localDateTime)
                            .atZone(TEST_ZONE)
                            .toInstant()
                            .toEpochMilli(),
                    timestampSource = TimestampSource.DATE_TAKEN,
                    width = 4032,
                    height = 3024,
                    orientation = 0,
                    durationMs = durationMs,
                    sizeBytes = 1_000_000L,
                    bucketId = bucketId,
                    bucketName = bucketName,
                    relativePath = "DCIM/Camera/",
                    volumeName = "external_primary",
                )
        }
    }

/** Ersetzt die öffentliche Repository-Bindung durch den Fake. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [MediaRepositoryModule::class])
object FakeMediaRepositoryModule {
    @Provides
    @Singleton
    fun provideMediaRepository(fake: FakeMediaRepository): MediaRepository = fake
}

/** Feste Uhr und Zeitzone, damit „Heute“ und „Gestern“ in Tests und Goldens immer gleich liegen. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimelineModule::class])
object FixedTimelineModule {
    @Provides
    fun provideClock(): Clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), TEST_ZONE)

    @Provides
    fun provideZoneId(): ZoneId = TEST_ZONE

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

/**
 * Vorschaubilder im Test: feste Farben pro Eintrag, damit keine echten Bilder geladen werden. Der Singleton-Loader
 * von Coil zeigt auf diesen Loader, bis [reset] aufgerufen wird.
 */
@OptIn(DelicateCoilApi::class)
object TestImageLoader {
    private val palette =
        listOf(
            0xFF8A4B2A.toInt(),
            0xFFB5652F.toInt(),
            0xFFD9893F.toInt(),
            0xFF6E3B24.toInt(),
            0xFFC97A36.toInt(),
            0xFF4A2A1C.toInt(),
        )

    fun install(items: List<MediaItem> = FakeMediaRepository.sampleItems()) {
        SingletonImageLoader.setSafe { context ->
            val engine =
                FakeImageLoaderEngine
                    .Builder()
                    .apply {
                        items.forEachIndexed { index, item ->
                            intercept(
                                MediaThumbnail(uri = item.uri, type = item.type),
                                ColorImage(
                                    palette[
                                        index %
                                            palette.size,
                                    ],
                                ),
                            )
                        }
                    }.build()
            ImageLoader
                .Builder(context)
                .components { add(engine) }
                .build()
        }
    }

    fun reset() {
        SingletonImageLoader.reset()
    }
}
