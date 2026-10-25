package io.github.labushuya.emberlight.netlab.payload

import kotlin.test.Test
import kotlin.test.assertEquals

class WifiQrTest {
    @Test
    fun encodesPlainNetwork() {
        assertEquals("WIFI:T:WPA;S:EL-Hotspot;P:geheimes1;;", WifiQr.encode("EL-Hotspot", "geheimes1"))
    }

    @Test
    fun escapesSpecialCharactersInSsidAndPassphrase() {
        val encoded = WifiQr.encode("A;B,C:D\"E\\F", "p;w,o:r\"d\\x")
        assertEquals("WIFI:T:WPA;S:A\\;B\\,C\\:D\\\"E\\\\F;P:p\\;w\\,o\\:r\\\"d\\\\x;;", encoded)
    }
}
