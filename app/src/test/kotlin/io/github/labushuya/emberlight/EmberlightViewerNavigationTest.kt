package io.github.labushuya.emberlight

import android.text.format.DateFormat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.labushuya.emberlight.core.media.model.MediaItem
import io.github.labushuya.emberlight.core.media.model.MediaType
import io.github.labushuya.emberlight.core.media.model.TimestampSource
import io.github.labushuya.emberlight.testing.FakeMediaRepository
import io.github.labushuya.emberlight.testing.TEST_ZONE
import io.github.labushuya.emberlight.testing.TestImageLoader
import io.github.labushuya.emberlight.testing.awaitAbsent
import io.github.labushuya.emberlight.testing.awaitDisplayed
import io.github.labushuya.emberlight.testing.awaitUi
import io.github.labushuya.emberlight.testing.clickWhenDisplayed
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Der Viewer in der App: Zeitleiste und Album öffnen den Viewer, Zurück führt zurück in dasselbe Ziel. Im Viewer ist die
 * Navigationsleiste ausgeblendet. Die Texte kommen aus den Ressourcen, der Test läuft auf Englisch.
 *
 * Jeder Schritt wartet auf den Zustand, den er braucht (siehe UiWaits.kt). Übergänge der Navigation und das Einblenden
 * der Titelleiste laufen nach einem Klick noch, eine sofortige Prüfung wäre deshalb nicht deterministisch.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, qualifiers = "en-w412dp-h915dp")
class EmberlightViewerNavigationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var repository: FakeMediaRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var hostZone: TimeZone? = null

    @Before
    fun setUp() {
        hiltRule.inject()
        TestImageLoader.install()
        // Der Test darf nicht von der Zeitzone des Rechners abhängen (CI läuft in UTC). Die Zone der Galerie kommt aus der
        // Injektion (TEST_ZONE), die Standardzone bleibt UTC.
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestImageLoader.reset()
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun timelineOpensTheViewerWithoutNavigationBarAndBackReturnsToTheTimeline() {
        launchApp()

        composeRule.clickWhenDisplayed(hasContentDescription(photoLabel(DAY_OF_PHOTO_8)))

        composeRule.awaitDisplayed(hasText(titleOf(PHOTO_8_TIME)))
        // Im Viewer ist die Navigationsleiste ausgeblendet: Ihr Eintrag für die Galerie fehlt.
        composeRule.awaitAbsent(isSelectable() and hasText(galleryLabel()))

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.awaitUi { selectable(galleryLabel()).assertIsSelected() }
        composeRule.awaitDisplayed(hasContentDescription(photoLabel(DAY_OF_PHOTO_8)))
    }

    @Test
    fun timelineBackKeepsTheTabAndTheScrollPosition() {
        repository.show(FakeMediaRepository.sampleLibrary(items = manyPhotos()))
        TestImageLoader.install(manyPhotos())
        launchApp()
        composeRule.clickWhenDisplayed(selectableMatcher(albumsLabel()))
        composeRule.clickWhenDisplayed(selectableMatcher(photosLabel()))

        // Ein Element weit unten, das man erst durch Scrollen erreicht.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(photoLabel(deepDate())))
        // Die Aktion auf dem Element selbst: Eine Koordinate läge nach dem Scrollen unter der Navigationsleiste.
        composeRule.awaitDisplayed(hasContentDescription(photoLabel(deepDate())))
        composeRule
            .onNodeWithContentDescription(
                photoLabel(deepDate()),
            ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.awaitDisplayed(hasText(titleOfDeep()))

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        composeRule.awaitUi { selectable(galleryLabel()).assertIsSelected() }
        // Der Reiter ist wieder Fotos, und das gesuchte Element liegt im sichtbaren Bereich.
        composeRule.awaitDisplayed(hasContentDescription(photoLabel(deepDate())))
    }

    @Test
    fun albumOpensTheViewerAndBackReturnsToTheAlbum() {
        launchApp()
        composeRule.clickWhenDisplayed(selectableMatcher(albumsLabel()))
        composeRule.clickWhenDisplayed(hasContentDescription(cameraCardLabel()))

        composeRule.clickWhenDisplayed(hasContentDescription(photoLabel(DAY_OF_PHOTO_8)))
        composeRule.awaitDisplayed(hasText(titleOf(PHOTO_8_TIME)))

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // Zurück im Album, nicht in der Übersicht und nicht im Viewer.
        composeRule.awaitDisplayed(hasText(cameraTitle()) and hasNoClickAction())
        composeRule.awaitDisplayed(hasContentDescription(context.getString(GalleryR.string.feature_gallery_back)))
    }

    @Test
    fun swipingInTheViewerChangesThePageTitle() {
        launchApp()
        composeRule.clickWhenDisplayed(hasContentDescription(photoLabel(DAY_OF_PHOTO_8)))

        composeRule.awaitDisplayed(hasText(titleOf(PHOTO_8_TIME)))
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // Der Index ist absteigend sortiert. Nach dem Foto von heute 08:15 kommt das Foto vom 15. Oktober 19:40.
        composeRule.awaitDisplayed(hasText(titleOf(PHOTO_7_TIME)))
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Titel des Viewers für einen Zeitpunkt, mit demselben Formatierer wie im Viewer. */
    private fun titleOf(time: LocalDateTime): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.US, "dMMMMyHHmm")
        return DateTimeFormatter.ofPattern(pattern, Locale.US).format(time.atZone(TEST_ZONE))
    }

    private fun titleOfDeep(): String = titleOf(deepDate().atTime(LocalTime.of(9, 30)))

    private fun selectableMatcher(label: String) = isSelectable() and hasText(label)

    private fun selectable(label: String) = composeRule.onNode(selectableMatcher(label))

    private fun albumsLabel() = context.getString(GalleryR.string.feature_gallery_tab_albums)

    private fun photosLabel() = context.getString(GalleryR.string.feature_gallery_tab_photos)

    private fun galleryLabel() = context.getString(R.string.app_nav_gallery)

    private fun cameraTitle() = "Camera"

    private fun cameraCardLabel(): String =
        context.getString(GalleryR.string.feature_gallery_album_label, "Camera", countLabel(4))

    private fun countLabel(count: Int): String =
        context.resources.getQuantityString(GalleryR.plurals.feature_gallery_album_items, count, count)

    /** Beschreibung eines Fotos im Raster: „Foto vom …“ mit dem Datum in der Sprache des Tests. */
    private fun photoLabel(day: LocalDate): String =
        context.getString(GalleryR.string.feature_gallery_cell_photo, dateText(day))

    private fun dateText(day: LocalDate): String = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US).format(day)

    private fun deepDate(): LocalDate = TODAY.minusDays(DEEP_INDEX.toLong())

    private fun manyPhotos(): List<MediaItem> =
        (0 until PHOTO_COUNT).map { day ->
            val id = 1000L + day
            val date = TODAY.minusDays(day.toLong())
            MediaItem(
                id = id,
                uri = "content://media/external/images/media/$id",
                type = MediaType.IMAGE,
                displayName = "img$id.jpg",
                mimeType = "image/jpeg",
                timestamp =
                    date
                        .atTime(LocalTime.of(9, 30))
                        .atZone(TEST_ZONE)
                        .toInstant()
                        .toEpochMilli(),
                timestampSource = TimestampSource.DATE_TAKEN,
                width = 4032,
                height = 3024,
                orientation = 0,
                durationMs = null,
                sizeBytes = 1_000_000L,
                bucketId = CAMERA_BUCKET,
                bucketName = "Camera",
                relativePath = "DCIM/Camera/",
                volumeName = "external_primary",
            )
        }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 16)
        val DAY_OF_PHOTO_8: LocalDate = LocalDate.of(2026, 10, 16)
        val PHOTO_8_TIME: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 10, 16, 8, 15)
        val PHOTO_7_TIME: java.time.LocalDateTime = java.time.LocalDateTime.of(2026, 10, 15, 19, 40)
        const val PHOTO_COUNT = 60
        const val DEEP_INDEX = 45
        const val CAMERA_BUCKET = 1L
    }
}
