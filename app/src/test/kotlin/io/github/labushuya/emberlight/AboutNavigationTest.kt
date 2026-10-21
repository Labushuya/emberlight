package io.github.labushuya.emberlight

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.labushuya.emberlight.testing.FakeMediaRepository
import io.github.labushuya.emberlight.testing.TestImageLoader
import io.github.labushuya.emberlight.testing.awaitDisplayed
import io.github.labushuya.emberlight.testing.awaitUi
import io.github.labushuya.emberlight.testing.clickWhenDisplayed
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject
import io.github.labushuya.emberlight.feature.gallery.R as GalleryR

/**
 * Weg zu „Über & Lizenzen“ über das Überlaufmenü der Galerie und zurück (ADR 0020). Die Galerie bleibt in der Leiste
 * markiert, solange die Seite offen ist.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class AboutNavigationTest {
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
    fun overflowMenuOpensAboutAndBackReturnsToGallery() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.clickWhenDisplayed(
            hasContentDescription(context.getString(GalleryR.string.feature_gallery_menu_more)),
        )
        composeRule.clickWhenDisplayed(hasText(context.getString(GalleryR.string.feature_gallery_menu_about)))

        composeRule.awaitDisplayed(hasText(context.getString(R.string.about_title)))
        composeRule.awaitDisplayed(hasText(context.getString(R.string.about_free_software)))

        composeRule.clickWhenDisplayed(hasContentDescription(context.getString(R.string.about_back)))

        composeRule.awaitDisplayed(hasText(context.getString(GalleryR.string.feature_gallery_header_today)))
        composeRule.awaitUi {
            onNode(isSelectable() and hasText(context.getString(R.string.app_nav_gallery))).assertIsSelected()
        }
    }
}
