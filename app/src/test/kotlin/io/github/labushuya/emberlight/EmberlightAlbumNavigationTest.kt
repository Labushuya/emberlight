package io.github.labushuya.emberlight

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.labushuya.emberlight.testing.FakeMediaRepository
import io.github.labushuya.emberlight.testing.TestImageLoader
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR
import io.github.labushuya.emberlight.feature.space.R as SpaceR

/**
 * Navigation der Alben in der App: Reiter, Album öffnen, Zurück, Wechsel zu Spaces und zurück. Dieselben Fake-Daten und
 * Ressourcen wie in [EmberlightAppNavigationTest]. Die Texte kommen aus den Ressourcen, damit der Test für Deutsch und
 * Englisch gleich bleibt.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class EmberlightAlbumNavigationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject
    lateinit var repository: FakeMediaRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        hiltRule.inject()
        TestImageLoader.install()
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestImageLoader.reset()
    }

    @Test
    fun albumOpensFromTheAlbumsTabAndBackReturnsToThatTab() {
        launchApp()
        selectable(albumsLabel()).performClick()

        composeRule.onNode(hasContentDescription(cameraCardLabel())).performClick()
        assertAlbumShown("Camera")
        selectable(galleryLabel()).assertIsSelected()

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        assertAlbumsTabShown()
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun albumStaysInTheGalleryAcrossSpacesAndBackKeepsTheTab() {
        launchApp()
        selectable(albumsLabel()).performClick()
        composeRule.onNode(hasContentDescription(cameraCardLabel())).performClick()
        assertAlbumShown("Camera")
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertAlbumsTabShown()

        selectable(spacesLabel()).performClick()
        assertSpacesShown()
        selectable(galleryLabel()).performClick()

        selectable(galleryLabel()).assertIsSelected()
        assertAlbumsTabShown()
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun albumOfTheItemsWithoutFolderIsNamedOtherAndSortedLast() {
        launchApp()
        selectable(albumsLabel()).performClick()

        composeRule.onNode(hasContentDescription(otherCardLabel())).performClick()

        assertAlbumShown(context.getString(GalleryR.string.feature_gallery_album_other))
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun germanAlbumsTabShowsGermanNamesAndCounts() {
        launchApp()
        selectable(context.getString(GalleryR.string.feature_gallery_tab_albums)).performClick()

        composeRule.onNodeWithText(context.getString(GalleryR.string.feature_gallery_album_other)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(cameraCardLabel())).assertIsDisplayed()
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun assertAlbumsTabShown() {
        selectable(albumsLabel()).assertIsSelected()
        composeRule.onNode(hasContentDescription(cameraCardLabel())).assertIsDisplayed()
    }

    private fun assertAlbumShown(title: String) {
        // Der Titel ist kein Knopf. Karten und Navigationseinträge sind Knöpfe und zählen hier nicht mit.
        composeRule.onNode(hasText(title) and hasNoClickAction()).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(GalleryR.string.feature_gallery_back))
            .assertIsDisplayed()
    }

    private fun assertSpacesShown() {
        composeRule
            .onNode(hasText(context.getString(SpaceR.string.feature_space_title)) and isHeading())
            .assertIsDisplayed()
    }

    /** Ein Reiter oder Navigationseintrag: auswählbar und mit dem Text. */
    private fun selectable(label: String) = composeRule.onNode(isSelectable() and hasText(label))

    private fun albumsLabel() = context.getString(GalleryR.string.feature_gallery_tab_albums)

    private fun galleryLabel() = context.getString(R.string.app_nav_gallery)

    private fun spacesLabel() = context.getString(R.string.app_nav_spaces)

    /** Beschreibung der Kamera-Karte, so wie die Alben-Übersicht sie in der Sprache des Tests bildet. */
    private fun cameraCardLabel(): String =
        context.getString(GalleryR.string.feature_gallery_album_label, "Camera", countLabel(4))

    private fun otherCardLabel(): String =
        context.getString(
            GalleryR.string.feature_gallery_album_label,
            context.getString(GalleryR.string.feature_gallery_album_other),
            countLabel(1),
        )

    private fun countLabel(count: Int): String =
        context.resources.getQuantityString(GalleryR.plurals.feature_gallery_album_items, count, count)
}
