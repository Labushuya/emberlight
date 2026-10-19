package io.github.labushuya.emberlight.feature.space

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.labushuya.emberlight.core.design.theme.EmberlightTheme
import io.github.labushuya.emberlight.feature.space.form.SpaceForm
import io.github.labushuya.emberlight.share.space.management.SpaceRecord
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

private const val GIB = 1024L * 1024L * 1024L

/**
 * Goldens der Spaces (Paket 3.2) in `src/test/screenshots` (ADR 0006). Zeitzone und Sprache sind fest, damit die Bilder
 * auf jedem Rechner gleich sind. Die Daten stehen in [SpaceFixtures].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpacesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var previousZone: TimeZone? = null
    private var previousLocale: Locale? = null

    @Before
    fun fixZoneAndLocale() {
        previousZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(SpaceFixtures.ZONE))
    }

    @After
    fun restoreZoneAndLocale() {
        previousZone?.let { TimeZone.setDefault(it) }
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun listEmptyPhoneEnglish() {
        capture(
            { SpacesListContent(emptyList(), null, { null }, {}, {}) },
            "src/test/screenshots/SpacesListEmpty_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun listFilledPhoneEnglish() {
        capture(
            { SpacesListContent(SpaceFixtures.sampleSpaces(), null, { null }, {}, {}) },
            "src/test/screenshots/SpacesListFilled_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun listFilledPhoneGerman() {
        capture(
            { SpacesListContent(SpaceFixtures.sampleSpaces(), null, { null }, {}, {}) },
            "src/test/screenshots/SpacesListFilled_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun listAndDetailTabletLandscapeEnglish() {
        val spaces = SpaceFixtures.sampleSpaces()
        capture(
            { ListAndDetail(spaces) },
            "src/test/screenshots/SpacesListAndDetail_tablet_landscape_en.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun detailPhoneEnglish() {
        capture(
            {
                SpaceDetailContent(
                    record = SpaceFixtures.sampleSpaces().first(),
                    coverFile = null,
                    onEdit = {},
                    onDelete = {},
                )
            },
            "src/test/screenshots/SpaceDetail_phone_en.png",
        )
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun detailPhoneGerman() {
        capture(
            {
                SpaceDetailContent(
                    record = SpaceFixtures.sampleSpaces().first(),
                    coverFile = null,
                    onEdit = {},
                    onDelete = {},
                )
            },
            "src/test/screenshots/SpaceDetail_phone_de.png",
        )
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun editPhoneEnglish() {
        capture({ EditForm() }, "src/test/screenshots/SpaceEdit_phone_en.png")
    }

    @Test
    @Config(qualifiers = "de-w412dp-h915dp")
    fun editPhoneGerman() {
        capture({ EditForm() }, "src/test/screenshots/SpaceEdit_phone_de.png")
    }

    @Test
    @Config(qualifiers = "en-w900dp-h600dp")
    fun editTabletLandscapeEnglish() {
        capture({ EditForm() }, "src/test/screenshots/SpaceEdit_tablet_landscape_en.png")
    }

    /** Liste und Detail nebeneinander, wie auf großen Displays in [SpacesScreen]. */
    @Composable
    private fun ListAndDetail(spaces: List<SpaceRecord>) {
        val first = spaces.first()
        Row(modifier = Modifier.fillMaxSize()) {
            SpacesListContent(
                spaces = spaces,
                selectedId = first.id.value,
                coverFile = { null },
                onSelect = {},
                onCreate = {},
                modifier = Modifier.weight(0.4f).fillMaxHeight(),
            )
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                SpaceDetailContent(record = first, coverFile = null, onEdit = {}, onDelete = {})
            }
        }
    }

    /** Formular für einen neuen Space mit einem Vorschlag, wie er nach der Eingabe aussieht. */
    @Composable
    private fun EditForm() {
        Surface(color = MaterialTheme.colorScheme.background) {
            SpaceEditContent(
                state =
                    SpaceEditUiState(
                        loaded = true,
                        isNew = true,
                        form = SpaceForm(name = "Hochzeit Lena und Tom", colorIndex = 2),
                        initial = SpaceForm(),
                        touched = emptySet(),
                        // Ein Gerät mit gut 14 GiB frei: 16 GiB ist ausgegraut, der Hinweis steht am Feld.
                        storageMaxBytes = 12 * GIB,
                    ),
                onChange = {},
                onLifetime = {},
                onPickCover = {},
                onRemoveCover = {},
                onSave = {},
                onClose = {},
                onConfirmDiscard = {},
                onDismissDiscard = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun capture(
        content: @Composable () -> Unit,
        path: String,
    ) {
        composeRule.setContent {
            EmberlightTheme {
                content()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(path)
    }
}
