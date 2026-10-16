package io.github.labushuya.emberlight.feature.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.core.media.MediaLibraryState
import io.github.labushuya.emberlight.core.media.MediaRepository
import io.github.labushuya.emberlight.feature.gallery.permission.DataStorePermissionPromptStore
import io.github.labushuya.emberlight.feature.gallery.permission.anyPermissionRationale
import io.github.labushuya.emberlight.feature.gallery.permission.appSettingsIntent
import io.github.labushuya.emberlight.feature.gallery.permission.findActivity
import io.github.labushuya.emberlight.feature.gallery.timeline.DataStoreZoomLevelStore
import io.github.labushuya.emberlight.feature.gallery.timeline.GroupingMode
import io.github.labushuya.emberlight.feature.gallery.timeline.TestThumbnails
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFormats
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineViewModel
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevelStore
import io.github.labushuya.emberlight.feature.gallery.timeline.ZoomLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Verdrahtung der Galerie: der Screen mit einem ViewModel auf festen Daten, der Einstellungs-Intent, die
 * Activity-Suche, das DataStore-Merkmal „schon gefragt“ und die Datumstexte in beiden Sprachen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GalleryWiringTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TestThumbnails.install(TimelineFixtures.sampleItems())
    }

    @After
    fun tearDown() {
        TestThumbnails.reset()
    }

    @Test
    fun screenShowsTheTimelineFromTheViewModel() {
        val viewModel =
            TimelineViewModel(
                repository = FixedRepository(MediaLibraryState.Ready(TimelineFixtures.library())),
                permissionPrompts = DataStorePermissionPromptStore(context),
                zoomLevels = MemoryZoomLevelStore(),
                defaultDispatcher = Dispatchers.Default,
                clock = TimelineFixtures.clock(),
                zone = TimelineFixtures.ZONE,
            )
        val todayLabel = context.getString(GalleryR.string.feature_gallery_header_today)

        composeRule.setContent {
            EmberlightTheme {
                GalleryScreen(viewModel = viewModel)
            }
        }

        composeRule.waitUntilAtLeastOneExists(hasText(todayLabel), timeoutMillis = 5_000)
        composeRule.onNodeWithText(todayLabel).assertIsDisplayed()
    }

    @Test
    fun settingsIntentOpensThisAppsDetailPage() {
        val intent = appSettingsIntent(context)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.data?.schemeSpecificPart)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun findActivityUnwrapsContextWrappers() {
        val activity = Activity()
        val wrapped = ContextWrapper(ContextWrapper(activity))

        assertSame(activity, wrapped.findActivity())
        assertNull(context.findActivity())
    }

    @Test
    fun rationaleIsFalseWithoutActivity() {
        val noActivity: Activity? = null

        assertFalse(noActivity.anyPermissionRationale(listOf("android.permission.READ_MEDIA_IMAGES")))
    }

    @Test
    fun askedFlagIsRememberedAcrossInstances() =
        runBlocking {
            assertFalse(DataStorePermissionPromptStore(context).askedBefore.first())

            DataStorePermissionPromptStore(context).markAsked()

            assertTrue(DataStorePermissionPromptStore(context).askedBefore.first())
        }

    @Test
    fun zoomLevelIsRememberedAcrossInstances() =
        runBlocking {
            assertEquals(ZoomLevels.DEFAULT_LEVEL, DataStoreZoomLevelStore(context).level.first())

            DataStoreZoomLevelStore(context).setLevel(ZoomLevels.MAX_LEVEL)

            assertEquals(ZoomLevels.MAX_LEVEL, DataStoreZoomLevelStore(context).level.first())
        }

    @Test
    fun scrollLabelsNameMonthOrYear() {
        val formats = TimelineFormats(Locale.GERMAN, TimelineFixtures.ZONE)
        val date = LocalDate.of(2026, 10, 16)

        assertEquals("Oktober 2026", formats.scrollLabel(GroupingMode.DAY, date))
        assertEquals("2026", formats.scrollLabel(GroupingMode.MONTH, date))
    }

    @Test
    fun englishDatesUseTheLocalePattern() {
        val formats = TimelineFormats(Locale.ENGLISH, TimelineFixtures.ZONE)

        assertEquals("Thursday, July 2", formats.dayHeader(LocalDate.of(2026, 7, 2), withYear = false))
        assertEquals("Thursday, July 2, 2026", formats.dayHeader(LocalDate.of(2026, 7, 2), withYear = true))
        assertEquals("October 2026", formats.monthHeader(YearMonth.of(2026, 10)))
        assertEquals("October 16, 2026", formats.longDate(TimelineFixtures.millis("2026-10-16T09:30")))
    }

    @Test
    fun germanDatesUseTheLocalePattern() {
        val formats = TimelineFormats(Locale.GERMAN, TimelineFixtures.ZONE)

        assertEquals("Donnerstag, 2. Juli", formats.dayHeader(LocalDate.of(2026, 7, 2), withYear = false))
        assertEquals("Oktober 2026", formats.monthHeader(YearMonth.of(2026, 10)))
        assertEquals("16. Oktober 2026", formats.longDate(TimelineFixtures.millis("2026-10-16T09:30")))
    }

    /** Stufe im Speicher. Der Verdrahtungstest prüft den Screen, nicht den Datenträger, daher ohne DataStore. */
    private class MemoryZoomLevelStore : ZoomLevelStore {
        private val levelFlow = MutableStateFlow(ZoomLevels.DEFAULT_LEVEL)

        override val level: Flow<Int> = levelFlow

        override suspend fun setLevel(level: Int) {
            levelFlow.value = level
        }
    }

    private class FixedRepository(
        initial: MediaLibraryState,
    ) : MediaRepository {
        override val library: StateFlow<MediaLibraryState> = MutableStateFlow(initial)

        override fun refresh() {
            // Feste Daten: nichts zu lesen.
        }
    }
}
