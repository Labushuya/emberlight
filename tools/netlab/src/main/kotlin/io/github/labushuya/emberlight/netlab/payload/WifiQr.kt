package io.github.labushuya.emberlight.netlab.payload

/**
 * Standard-WLAN-QR-Code (`WIFI:T:WPA;S:…;P:…;;`) für Gäste ohne Netlab-App (Browser-Gäste, Paket 4.0, Stufe 3).
 *
 * Sonderzeichen `\ ; , : "` werden mit Backslash maskiert, wie es die gängigen Scanner erwarten. Reine Funktion.
 */
object WifiQr {
    /** Liefert den Inhalt für den QR-Code. Die SSID und das Passwort werden maskiert übernommen. */
    fun encode(
        ssid: String,
        passphrase: String,
    ): String = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"

    internal fun escape(value: String): String =
        buildString {
            for (char in value) {
                if (char in ESCAPED) append('\\')
                append(char)
            }
        }

    private const val ESCAPED = "\\;,:\""
}
