package io.github.labushuya.emberlight.feature.gallery.viewer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.memory.MemoryCache
import io.github.labushuya.emberlight.feature.gallery.timeline.TimelineFixtures
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Der Store der Bildschirm-Vorschau (ADR 0010, Korrektur 4): Das gezeigte Element zuerst, die Nachbarn erst danach, ein
 * Seitenwechsel bricht überflüssige Ladevorgänge ab und entfernt ihre Einträge aus dem Speicher-Cache, `release` gibt alles frei.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
@OptIn(ExperimentalTestApi::class)
class ScreenPreviewsComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val items = TimelineFixtures.sampleItems().map { it.toViewerItem() }
    private val fakes = ScreenPreviewFakes()
    private val loader by lazy { fakes.loader(context) }
    private val marks = mutableListOf<String>()
    private lateinit var store: ScreenPreviews

    @Before
    fun setUp() {
        ViewerTrace.output = { line -> marks += line.substringBefore(" id=") }
    }

    @After
    fun tearDown() {
        ViewerTrace.output = { _ -> }
    }

    @Test
    fun theShownItemLoadsFirstAndItsNeighboursOnlyAfterIt() {
        show()

        composeRule.runOnIdle { store.focus(items, 1) }
        assertNull("Vor dem gezeigten Element lädt kein Nachbar.", store.bitmapOf(items[0].uri))
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[1].uri) != null }

        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            store.bitmapOf(items[0].uri) != null && store.bitmapOf(items[2].uri) != null
        }
        assertFalse("Nur die direkten Nachbarn ±1 laden.", fakes.requested.contains(items[3].uri))
    }

    @Test
    fun aPageChangeDropsWhatIsNoLongerNeededFromTheStoreAndTheCache() {
        show()
        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[1].uri) != null }
        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[0].uri) != null }

        composeRule.runOnIdle { store.focus(items, 4) }

        composeRule.runOnIdle {
            assertNull("Das alte Element ist nicht mehr gehalten.", store.bitmapOf(items[0].uri))
            assertNull("Der Speicher-Cache behält es nicht.", cacheEntry(items[0]))
        }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[4].uri) != null }
    }

    @Test
    fun releaseFreesEverythingItLoaded() {
        show()
        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[1].uri) != null }
        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[2].uri) != null }

        composeRule.runOnIdle {
            store.release()
            assertNull(store.bitmapOf(items[1].uri))
            assertNull(cacheEntry(items[1]))
            assertNull(cacheEntry(items[2]))
        }
    }

    @Test
    fun theArrivalIsMarkedAfterTheRequest() {
        show()

        composeRule.runOnIdle { store.focus(items, 1) }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { store.bitmapOf(items[1].uri) != null }

        assertTrue("Angefordert, dann bereit: $marks", marks.indexOf("Bildschirm-Vorschau angefordert") == 0)
        assertTrue("Bereit nach der Anforderung: $marks", marks.contains("Bildschirm-Vorschau bereit"))
        assertNotNull(store.bitmapOf(items[1].uri))
    }

    /** Lädt den Store für die Komposition, mit dem Test-Lader. */
    private fun show() {
        composeRule.setContent { StoreHost() }
        composeRule.waitForIdle()
    }

    @Composable
    private fun StoreHost() {
        val scope: CoroutineScope = rememberCoroutineScope()
        store = remember(scope) { ScreenPreviews(scope, loader, context) }
    }

    /** Eintrag des Speicher-Cache für ein Element, `null` wenn er fehlt. */
    private fun cacheEntry(item: ViewerItem): MemoryCache.Value? =
        loader.memoryCache?.get(MemoryCache.Key(screenPreviewKey(item.uri)))

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
