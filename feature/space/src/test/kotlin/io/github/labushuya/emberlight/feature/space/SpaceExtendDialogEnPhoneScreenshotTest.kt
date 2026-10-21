package io.github.labushuya.emberlight.feature.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.labushuya.emberlight.feature.space.run.ExtendDialogContent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Golden des Verlängern-Dialogs (Paket 3.6a). Ein Golden je Klasse (siehe [SpaceRunScreenshotSupport]). Die Zeitzone ist UTC,
 * damit das angezeigte Ende auf jedem Rechner gleich aussieht.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp")
class SpaceExtendDialogEnPhoneScreenshotTest : SpaceRunScreenshotSupport() {
    private lateinit var hostZone: TimeZone

    @Before
    fun setZone() {
        hostZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(hostZone)
    }

    @Test
    fun extendDialog() {
        capture({
            DialogBody(title = "Extend space") {
                ExtendDialogContent(
                    currentEndsAtMillis = FIXED_END,
                    problem = null,
                    onChoose = {},
                    customStart = false,
                )
            }
        }, "src/test/screenshots/SpaceExtendDialog_phone_en.png")
    }

    private companion object {
        /** Ein fester Zeitpunkt für das aktuelle Ende. */
        const val FIXED_END = 1_797_000_000_000L
    }
}
