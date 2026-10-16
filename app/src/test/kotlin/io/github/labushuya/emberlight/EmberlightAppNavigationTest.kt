package io.github.labushuya.emberlight

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR
import io.github.labushuya.emberlight.feature.space.R as SpaceR

/**
 * Navigation der App mit Hilt und Robolectric. Gestartet wird die registrierte, Hilt-fähige MainActivity. Der Test
 * setzt auf die Ressourcen der Module, nicht auf fest eingetragene Texte, damit er für Deutsch und Englisch gleich bleibt.
 *
 * Hilt liefert den Medienindex als Fake mit festen Daten (siehe TestSeams). Der Compose-Rule setzt keinen eigenen
 * Inhalt, weil MainActivity ihn selbst setzt. Die Navigation der Alben steht in [EmberlightAlbumNavigationTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class EmberlightAppNavigationTest {
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
    fun startsOnTimeline() {
        launchApp()

        assertTimelineShown()
        navItem(galleryLabel()).assertIsSelected()
    }

    @Test
    fun tappingSpacesShowsSpacesPlaceholder() {
        launchApp()

        navItem(spacesLabel()).performClick()

        assertSpacesShown()
        navItem(spacesLabel()).assertIsSelected()
    }

    @Test
    fun systemBackFromSpacesReturnsToGallery() {
        launchApp()
        navItem(spacesLabel()).performClick()
        assertSpacesShown()

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        assertTimelineShown()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanLocaleShowsGermanTexts() {
        assertEquals("Galerie", galleryLabel())
        launchApp()

        assertTimelineShown()
        navItem("Galerie").assertIsSelected()
        navItem(spacesLabel()).performClick()
        assertSpacesShown()
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun navItem(label: String) = composeRule.onNode(isSelectable() and hasText(label))

    private fun galleryLabel() = context.getString(R.string.app_nav_gallery)

    private fun spacesLabel() = context.getString(R.string.app_nav_spaces)

    private fun assertTimelineShown() {
        composeRule
            .onNode(hasText(context.getString(GalleryR.string.feature_gallery_title)) and hasNoClickAction())
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(GalleryR.string.feature_gallery_header_today))
            .assertIsDisplayed()
    }

    private fun assertSpacesShown() {
        composeRule
            .onNode(hasText(context.getString(SpaceR.string.feature_space_title)) and isHeading())
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(SpaceR.string.feature_space_text)).assertIsDisplayed()
    }
}
