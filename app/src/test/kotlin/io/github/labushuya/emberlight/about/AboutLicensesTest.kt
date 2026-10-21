package io.github.labushuya.emberlight.about

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Laden und Auswerten der Lizenzliste (ADR 0020). Die Auswertung prüft die JSON-Fälle. Die Assets prüfen die Schrift- und
 * GPL-Texte aus `core:design`.
 */
@RunWith(AndroidJUnit4::class)
class AboutLicensesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun parsesLibrariesSortedByName() {
        val list = parseLibraryList(AboutFixtures.LIBRARY_JSON) as LibraryList.Loaded

        assertEquals(listOf("Alpha", "Bare", "Zeta"), list.libraries.map { it.name })
    }

    @Test
    fun resolvesVersionAndLicenseTextForEachLibrary() {
        val list = parseLibraryList(AboutFixtures.LIBRARY_JSON) as LibraryList.Loaded
        val alpha = list.libraries.single { it.name == "Alpha" }

        assertEquals("1.0.0", alpha.version)
        assertEquals(listOf("MIT License"), alpha.licenses.map { it.name })
        assertEquals("MIT test text", alpha.licenses.single().text)
    }

    @Test
    fun keepsLibraryWithoutLicenseInTheList() {
        // Eine Bibliothek ohne Lizenzangabe verschwindet nicht still. Sie bleibt mit leerer Lizenzliste sichtbar.
        val list = parseLibraryList(AboutFixtures.LIBRARY_JSON) as LibraryList.Loaded
        val bare = list.libraries.single { it.name == "Bare" }

        assertTrue(bare.licenses.isEmpty())
    }

    @Test
    fun invalidJsonIsUnreadableAndDoesNotThrow() {
        assertEquals(LibraryList.Unreadable, parseLibraryList("{ nicht json"))
    }

    @Test
    fun emptyInputIsUnreadable() {
        assertEquals(LibraryList.Unreadable, parseLibraryList(""))
    }

    @Test
    fun releaseListHasNoTestOrToolingDependencies() {
        // Die Liste kommt aus der Release-Laufzeit (ADR 0020). Unit-Tests laufen im Debug-Build, dort darf sie nicht anders sein.
        val list = loadLibraryList(context) as LibraryList.Loaded
        val ids = list.libraries.map { it.id }

        assertTrue(ids.isNotEmpty())
        val forbiddenExact = setOf("androidx.compose.ui:ui-tooling", "androidx.compose.ui:ui-test-manifest")
        val forbiddenPrefix =
            listOf("org.robolectric", "junit:", "androidx.test", "app.cash.turbine", "io.github.takahirom")
        assertTrue(ids.none { it in forbiddenExact || forbiddenPrefix.any { prefix -> it.startsWith(prefix) } })
    }

    @Test
    fun readsFontLicenseFromAssets() {
        val text = readAsset(context, "licenses/fraunces_OFL.txt")

        assertNotNull(text)
        assertTrue(text.orEmpty().contains("SIL OPEN FONT LICENSE", ignoreCase = true))
    }

    @Test
    fun missingAssetIsNullNotAnException() {
        assertNull(readAsset(context, "licenses/gibt-es-nicht.txt"))
    }

    @Test
    fun aboutContentContainsBothFontsAndGpl() {
        val content = loadAboutContent(context)

        assertEquals(listOf("Fraunces", "Inter"), content.fonts.map { it.title })
        assertTrue(content.fonts.all { !it.text.isNullOrBlank() })
        assertTrue(
            content.gpl.text
                .orEmpty()
                .contains("GNU GENERAL PUBLIC LICENSE"),
        )
    }
}
