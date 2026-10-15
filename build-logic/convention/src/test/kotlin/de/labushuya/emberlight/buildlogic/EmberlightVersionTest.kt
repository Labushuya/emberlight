package de.labushuya.emberlight.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmberlightVersionTest {
    @Test
    fun zeroOneZeroMapsToCode100() {
        val version = EmberlightVersion.parse("0.1.0")
        assertEquals("0.1.0", version.name)
        assertEquals(100, version.code)
    }

    @Test
    fun oneFourTwoMapsToCode10402() {
        val version = EmberlightVersion.parse("1.4.2")
        assertEquals("1.4.2", version.name)
        assertEquals(10402, version.code)
    }

    @Test
    fun largeValidVersionMapsCorrectly() {
        assertEquals(129999, EmberlightVersion.parse("12.99.99").code)
    }

    @Test
    fun maximumMinorAndPatchAreAccepted() {
        assertEquals(9999, EmberlightVersion.parse("0.99.99").code)
    }

    @Test
    fun minorOf100IsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.100.0") }
    }

    @Test
    fun patchOf100IsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("1.0.100") }
    }

    @Test
    fun majorAtIntLimitIsAccepted() {
        assertEquals(2147479999, EmberlightVersion.parse("214747.99.99").code)
    }

    @Test
    fun majorBeyondIntLimitIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("214748.0.0") }
    }

    @Test
    fun numberBeyondLongRangeIsRejectedWithoutOverflow() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.parse("99999999999999999999.0.0") }
    }

    @Test
    fun invalidFormatsAreRejected() {
        val invalid =
            listOf(
                "",
                "v0.1.0",
                "0.1.0-rc1",
                "0.1.0+42",
                " 0.1.0",
                "0.1.0 ",
                "0.1",
                "0.1.0.0",
                "01.2.3",
                "1.02.3",
                "1.2.-3",
                "a.b.c",
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
        assertEquals(100, EmberlightVersion.resolve("0.1.0").code)
    }

    @Test
    fun emptyPropertyIsRejected() {
        assertFailsWith<IllegalArgumentException> { EmberlightVersion.resolve("") }
    }
}
