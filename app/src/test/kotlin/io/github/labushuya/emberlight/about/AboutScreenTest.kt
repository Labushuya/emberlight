package io.github.labushuya.emberlight.about

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.R
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.testing.awaitUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verhalten des Bildschirms „Über & Lizenzen“ (ADR 0020): Anzeige, Lizenzdialoge, Quellcode-Link und die Hinweise, wenn die
 * Liste fehlt oder nicht lesbar ist. Die Texte kommen aus den Ressourcen, damit der Test für Deutsch und Englisch gleich bleibt.
 * Die Größe ist die eines Telefons, damit Dialoge ganz im Fenster liegen.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-w412dp-h1200dp")
class AboutScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun showsAppNameVersionAndFreeSoftwareNotice() {
        setScreen(AboutFixtures.CONTENT)

        composeRule.onNodeWithText(context.getString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version, AboutFixtures.VERSION)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_free_software)).assertIsDisplayed()
    }

    @Test
    fun listsLibrariesWithVersionAndLicense() {
        setScreen(AboutFixtures.CONTENT)

        // Die Liste ist ein LazyColumn. Die letzte Bibliothek liegt unterhalb des Bildschirms, also dorthin scrollen.
        scrollTo("Zeta").assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.about_library_detail, "2.0.0", "Apache License 2.0"))
            .assertIsDisplayed()
    }

    @Test
    fun libraryWithoutLicenseSaysLicenseUnknown() {
        setScreen(AboutFixtures.CONTENT)

        val unknown = context.getString(R.string.about_license_unknown)
        composeRule
            .onNodeWithText(context.getString(R.string.about_library_detail, "0.1.0", unknown))
            .assertIsDisplayed()
    }

    @Test
    fun tappingLibraryShowsItsLicenseText() {
        setScreen(AboutFixtures.CONTENT)

        composeRule.onNodeWithText("Alpha").performClick()

        composeRule.awaitUi { onNodeWithText("MIT test text").assertIsDisplayed() }
    }

    @Test
    fun tappingFontShowsItsLicenseText() {
        setScreen(AboutFixtures.CONTENT)

        composeRule.onNodeWithText("Inter").performClick()
        composeRule.waitForIdle()

        composeRule.awaitUi { onNodeWithText("Inter test text").assertIsDisplayed() }
    }

    @Test
    fun gplTextIsReachableFromTheScreen() {
        setScreen(AboutFixtures.CONTENT)

        composeRule.onNodeWithText(context.getString(R.string.about_gpl_show)).performClick()
        composeRule.waitForIdle()

        composeRule.awaitUi { onNodeWithText("GNU GENERAL PUBLIC LICENSE test text").assertIsDisplayed() }
    }

    @Test
    fun sourceButtonOpensRepositoryInBrowserIntent() {
        setScreen(AboutFixtures.CONTENT)

        composeRule.onNodeWithText(context.getString(R.string.about_source_button)).performClick()

        val started: Intent? = shadowOf(composeRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(SOURCE_URL, started.dataString)
    }

    @Test
    fun buildWithoutLibraryListSaysSo() {
        setScreen(AboutFixtures.CONTENT.copy(libraries = LibraryList.NotIncluded))

        composeRule.onNodeWithText(context.getString(R.string.about_libraries_not_included)).assertIsDisplayed()
    }

    @Test
    fun unreadableLibraryListSaysSo() {
        setScreen(AboutFixtures.CONTENT.copy(libraries = LibraryList.Unreadable))

        composeRule.onNodeWithText(context.getString(R.string.about_libraries_unreadable)).assertIsDisplayed()
    }

    /** Scrollt die Liste, bis der Knoten mit [text] komponiert ist. Nur komponierte Einträge sind im Baum sichtbar. */
    private fun scrollTo(text: String): SemanticsNodeInteraction =
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))

    private fun setScreen(content: AboutContent) {
        composeRule.setContent {
            EmberlightTheme {
                AboutScreen(onBack = {}, content = content)
            }
        }
    }
}
