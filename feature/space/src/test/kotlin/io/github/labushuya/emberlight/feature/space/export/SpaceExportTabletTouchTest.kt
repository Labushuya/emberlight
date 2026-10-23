package io.github.labushuya.emberlight.feature.space.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Touch-Test: breites Display (Tablet, Querformat) (Paket 3.7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-w840dp-h900dp")
class SpaceExportTabletTouchTest : SpaceExportTouchSupport()
