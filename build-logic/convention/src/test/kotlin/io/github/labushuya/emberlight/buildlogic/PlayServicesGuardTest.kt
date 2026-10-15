package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayServicesGuardTest {
    @Test
    fun playServicesCoordinatesAreReported() {
        val coordinates =
            listOf(
                "com.google.android.gms:play-services-basement:18.1.0",
                "com.google.firebase:firebase-messaging:23.0.0",
                "com.google.mlkit:barcode-scanning:17.0.0",
                "com.google.android.play:core:1.10.3",
            )
        assertEquals(coordinates, PlayServicesGuard.findViolations(coordinates))
    }

    @Test
    fun subgroupsOfForbiddenGroupsAreReported() {
        val coordinates = listOf("com.google.android.gms.internal:something:1.0.0")
        assertEquals(coordinates, PlayServicesGuard.findViolations(coordinates))
    }

    @Test
    fun unrelatedGroupsAreNotReported() {
        val coordinates =
            listOf(
                "com.google.android.material:material:1.12.0",
                "com.google.dagger:hilt-android:2.60.1",
                "com.google.devtools.ksp:symbol-processing:2.3.12",
                "androidx.core:core-ktx:1.19.1",
            )
        assertTrue(PlayServicesGuard.findViolations(coordinates).isEmpty())
    }

    @Test
    fun groupMatchRequiresExactPrefixSegment() {
        assertFalse(PlayServicesGuard.isForbiddenGroup("com.google.android.gmsx"))
        assertTrue(PlayServicesGuard.isForbiddenGroup("com.google.android.gms"))
    }

    @Test
    fun onlyViolatingCoordinatesAreReturnedFromMixedInput() {
        val coordinates =
            listOf(
                "androidx.compose.ui:ui:1.10.0",
                "com.google.firebase:firebase-core:21.0.0",
            )
        assertEquals(listOf("com.google.firebase:firebase-core:21.0.0"), PlayServicesGuard.findViolations(coordinates))
    }
}
