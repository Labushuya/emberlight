package io.github.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmberlightVersionTest {
    @Test
    fun finalVersionMapsToStage99() {
        val version = EmberlightVersion.parse("0.2.0")
        assertEquals("0.2.0", version.name)
        assertEquals(20099, version.code)
    }

    @Test
    fun alphaVersionMapsToStageN() {
        val version = EmberlightVersion.parse("0.3.0-alpha.1")
        assertEquals("0.3.0-alpha.1", version.name)
        assertEquals(30001, version.code)
    }

    @Test
    fun finalVersionAfterPrereleasesMapsToStage99() {
        assertEquals(30099, EmberlightVersion.parse("0.3.0").code)
    }

    @Test
    fun betaVersionsUseOffset20() {
        assertEquals(30021, EmberlightVersion.parse("0.3.0-beta.1").code)
        assertEquals(30039, EmberlightVersion.parse("0.3.0-beta.19").code)
    }

    @Test
    fun rcVersionsUseOffset40() {
        assertEquals(30041, EmberlightVersion.parse("0.3.0-rc.1").code)
        assertEquals(30059, EmberlightVersion.parse("0.3.0-rc.19").code)
    }

    @Test
    fun alphaBoundsAreAccepted() {
        assertEquals(30019, EmberlightVersion.parse("0.3.0-alpha.19").code)
    }

    @Test
    fun largeValidVersionMapsCorrectly() {
        assertEquals(12_999_999, EmberlightVersion.parse("12.99.99").code)
    }

    @Test
    fun maximumMinorAndPatchAreAccepted() {
        assertEquals(999_999, EmberlightVersion.parse("0.99.99").code)
        assertEquals(999_959, EmberlightVersion.parse("0.99.99-rc.19").code)
    }

    @Test
    fun maximumMajorStaysBelowGooglePlayLimit() {
        val version = EmberlightVersion.parse("2099.99.99")
        assertEquals(2_099_999_999, version.code)
        assertTrue(version.code <= GOOGLE_PLAY_MAX_VERSION_CODE)
        assertEquals(2_099_999_901, EmberlightVersion.parse("2099.99.99-alpha.1").code)
    }

    @Test
    fun majorBeyondLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("2100.0.0") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("2100.0.0-alpha.1") }
    }

    @Test
    fun minorOf100IsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.100.0") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.100.0-alpha.1") }
    }

    @Test
    fun patchOf100IsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.0.100") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.0.100-rc.1") }
    }

    @Test
    fun prereleaseNumberOutOfRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-alpha.0") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-alpha.20") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-beta.20") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-rc.100") }
    }

    @Test
    fun numberBeyondLongRangeIsRejectedWithoutOverflow() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("99999999999999999999.0.0") }
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-alpha.99999999999999999999") }
    }

    @Test
    fun unknownPrereleaseKindIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("0.3.0-gamma.1") }
    }

    @Test
    fun vPrefixIsRejected() {
        val invalid = listOf("v0.3.0", "v0.3.0-alpha.1", "v0.3.0-alpha", "v1.2", "v01.2.3")
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException>("erwartet Fehler für '$raw'") {
                EmberlightVersion.parse(raw)
            }
        }
    }

    @Test
    fun invalidFormatsAreRejected() {
        val invalid =
            listOf(
                "",
                "0.1",
                "1.2",
                "0.1.0.0",
                "01.2.3",
                "1.02.3",
                "1.2.-3",
                "a.b.c",
                "0.3.0-alpha",
                "0.3.0-alpha.",
                "0.3.0-alpha.01",
                "0.3.0-alpha.1.2",
                "0.3.0-alpha.-1",
                "0.3.0-ALPHA.1",
                "0.3.0-rc",
                "0.3.0-rc1",
                "0.3.0-rc.1-beta.1",
                "0.1.0+42",
                " 0.1.0",
                "0.1.0 ",
                "0.3.0-alpha.1 ",
                "0.3.0-alpha.1\n",
            )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException>("erwartet Fehler für '$raw'") {
                EmberlightVersion.parse(raw)
            }
        }
    }

    @Test
    fun missingPropertyFallsBackToDevVersion() {
        val version = EmberlightVersion.resolve(null)
        assertEquals("0.0.0-dev", version.name)
        assertEquals(1, version.code)
    }

    @Test
    fun presentPropertyIsParsed() {
        assertEquals(30001, EmberlightVersion.resolve("0.3.0-alpha.1").code)
    }

    @Test
    fun emptyPropertyIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.resolve("") }
    }

    @Test
    fun versionCodesIncreaseAlongSemverOrder() {
        // Aufsteigend nach SemVer-Vorrang: Vorabversionen vor ihrer finalen Version, alpha < beta < rc.
        val ordered =
            listOf(
                "0.1.0",
                "0.2.0",
                "0.2.1-alpha.1",
                "0.2.1-alpha.2",
                "0.2.1-alpha.19",
                "0.2.1-beta.1",
                "0.2.1-beta.19",
                "0.2.1-rc.1",
                "0.2.1-rc.19",
                "0.2.1",
                "0.2.2-alpha.1",
                "0.3.0-alpha.1",
                "0.3.0-alpha.2",
                "0.3.0-beta.1",
                "0.3.0-rc.1",
                "0.3.0",
                "0.3.1-alpha.1",
                "0.4.0-alpha.1",
                "1.0.0-alpha.1",
                "1.0.0",
                "1.0.1",
                "1.1.0",
                "2099.99.99",
            )
        val codes = ordered.map { EmberlightVersion.parse(it).code }
        codes.zipWithNext().forEachIndexed { index, (previous, next) ->
            assertTrue(
                previous < next,
                "'${ordered[index]}' ($previous) muss kleiner sein als '${ordered[index + 1]}' ($next)",
            )
        }
    }

    @Test
    fun newCodesStayAboveLegacyReleaseCodes() {
        // Veröffentlicht sind 0.1.0 (Code 100) und 0.2.0 (Code 200) nach dem alten Schema.
        // Neue Codes ab 0.2.0 liegen darüber.
        assertTrue(EmberlightVersion.parse("0.2.0-alpha.1").code > 200)
        assertTrue(EmberlightVersion.parse("0.2.0").code > 200)
        assertTrue(EmberlightVersion.parse("0.1.1-alpha.1").code > 200)
    }

    private companion object {
        const val GOOGLE_PLAY_MAX_VERSION_CODE = 2_100_000_000
    }
}
