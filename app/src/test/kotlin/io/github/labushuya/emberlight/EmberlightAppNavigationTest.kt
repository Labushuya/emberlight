package io.github.labushuya.emberlight

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR
import io.github.labushuya.emberlight.feature.space.R as SpaceR

/**
 * Navigation der App mit Robolectric. Der Test setzt auf die Ressourcen der Module und nicht auf
 * fest eingetragene Texte, damit er für Deutsch und Englisch gleich bleibt.
 *
 * Statt createComposeRule() wird createAndroidComposeRule<ComponentActivity>() genutzt: Der System-Back-Test
 * braucht Zugriff auf die Activity und ihren OnBackPressedDispatcher.
 */
@RunWith(AndroidJUnit4::class)
class EmberlightAppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startsOnGalleryPlaceholder() {
        setApp()

        assertGalleryShown()
        navItem(galleryLabel()).assertIsSelected()
    }

    @Test
    fun tappingSpacesShowsSpacesPlaceholder() {
        setApp()

        navItem(spacesLabel()).performClick()

        assertSpacesShown()
        navItem(spacesLabel()).assertIsSelected()
    }

    @Test
    fun systemBackFromSpacesReturnsToGallery() {
        setApp()
        navItem(spacesLabel()).performClick()
        assertSpacesShown()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        assertGalleryShown()
    }

    @Test
    @Config(qualifiers = "de")
    fun germanLocaleShowsGermanTexts() {
        assertEquals("Galerie", composeRule.activity.getString(R.string.app_nav_gallery))
        setApp()

        assertGalleryShown()
        navItem("Galerie").assertIsSelected()
        navItem(spacesLabel()).performClick()
        assertSpacesShown()
    }

    private fun setApp() {
        composeRule.setContent {
            EmberlightTheme {
                EmberlightApp()
            }
        }
    }

    private fun navItem(label: String) = composeRule.onNode(isSelectable() and hasText(label))

    private fun galleryLabel() = composeRule.activity.getString(R.string.app_nav_gallery)

    private fun spacesLabel() = composeRule.activity.getString(R.string.app_nav_spaces)

    private fun assertGalleryShown() {
        composeRule
            .onNode(hasText(composeRule.activity.getString(GalleryR.string.feature_gallery_title)) and isHeading())
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(
                composeRule.activity.getString(GalleryR.string.feature_gallery_text),
            ).assertIsDisplayed()
    }

    private fun assertSpacesShown() {
        composeRule
            .onNode(hasText(composeRule.activity.getString(SpaceR.string.feature_space_title)) and isHeading())
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(SpaceR.string.feature_space_text)).assertIsDisplayed()
    }
}
