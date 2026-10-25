package io.github.labushuya.emberlight.netlab.payload

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64

/**
 * Daten, die der Gastgeber per QR-Code an die Netlab-App des Gasts übergibt (Paket 4.0, Stufe 1).
 *
 * Format: `emberlight-netlab://join?v=1&ssid=…&pass=…&host=…&port=…&token=…`. Die Werte sind URL-kodiert. Die Daten
 * verlassen das Gerät nicht, der QR-Code wird lokal angezeigt und gescannt.
 *
 * Reine Funktion ohne Android-Abhängigkeit, damit sie unit-testbar bleibt.
 */
data class JoinPayload(
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    fun encode(): String {
        val query =
            listOf(
                "v" to VERSION.toString(),
                "ssid" to ssid,
                "pass" to passphrase,
                "host" to host,
                "port" to port.toString(),
                "token" to token,
            ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }
        return "$SCHEME://$AUTHORITY?$query"
    }

    companion object {
        const val SCHEME = "emberlight-netlab"
        const val AUTHORITY = "join"
        const val VERSION = 1

        /**
         * WPA2-Passphrase: 8 bis 63 Zeichen (IEEE 802.11i). Hotspot-Passwörter von Android liegen im selben Bereich.
         */
        const val PASSPHRASE_MIN = 8
        const val PASSPHRASE_MAX = 63

        /** SSID: 1 bis 32 Bytes (IEEE 802.11). */
        const val SSID_MAX_BYTES = 32

        private const val TOKEN_MIN = 16
        private const val TOKEN_MAX = 64
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{$TOKEN_MIN,$TOKEN_MAX}$")
        private val IPV4_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

        /** Erzeugt ein Token aus 16 Zufallsbytes (Base64-URL ohne Auffüllzeichen). */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(TOKEN_MIN)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** Liest den Payload. Liefert bei Fehlern den Grund, keine Ausnahme. */
        fun decode(raw: String): DecodeResult {
            val trimmed = raw.trim()
            val prefix = "$SCHEME://$AUTHORITY?"
            if (!trimmed.startsWith(prefix)) return DecodeResult.Failure(PayloadError.WRONG_FORMAT)
            val params =
                parseQuery(trimmed.removePrefix(prefix)) ?: return DecodeResult.Failure(PayloadError.WRONG_FORMAT)

            if (params["v"] != VERSION.toString()) return DecodeResult.Failure(PayloadError.WRONG_VERSION)
            val ssid = params["ssid"]
            val pass = params["pass"]
            val host = params["host"]
            val port = params["port"]?.toIntOrNull()
            val token = params["token"]
            if (ssid == null || pass == null || host == null || port == null || token == null) {
                return DecodeResult.Failure(PayloadError.MISSING_FIELD)
            }
            if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > SSID_MAX_BYTES) {
                return DecodeResult.Failure(PayloadError.INVALID_SSID)
            }
            if (pass.length !in PASSPHRASE_MIN..PASSPHRASE_MAX) {
                return DecodeResult.Failure(PayloadError.INVALID_PASSPHRASE)
            }
            if (!isIpv4(host)) return DecodeResult.Failure(PayloadError.INVALID_HOST)
            if (port !in PORT_MIN..PORT_MAX) return DecodeResult.Failure(PayloadError.INVALID_PORT)
            if (!TOKEN_PATTERN.matches(token)) return DecodeResult.Failure(PayloadError.INVALID_TOKEN)

            return DecodeResult.Success(JoinPayload(ssid, pass, host, port, token))
        }

        /** Nur Ziffern-Punkt-Form, je Teil 0 bis 255. Keine Namen, keine IPv6-Adressen. */
        fun isIpv4(value: String): Boolean {
            val match = IPV4_PATTERN.matchEntire(value) ?: return false
            return match.groupValues.drop(1).all { part -> part.toInt() in 0..255 }
        }

        private const val PORT_MIN = 1024
        private const val PORT_MAX = 65535

        /** Zerlegt die Abfrage. Doppelte Schlüssel oder fehlerhafte Kodierung ergeben `null`. */
        private fun parseQuery(query: String): Map<String, String>? {
            val result = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val index = part.indexOf('=')
                if (index <= 0) return null
                val key = part.substring(0, index)
                if (result.containsKey(key)) return null
                val value =
                    try {
                        URLDecoder.decode(part.substring(index + 1), Charsets.UTF_8.name())
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                result[key] = value
            }
            return result
        }
    }

    sealed interface DecodeResult {
        data class Success(
            val payload: JoinPayload,
        ) : DecodeResult

        data class Failure(
            val reason: PayloadError,
        ) : DecodeResult
    }
}

/** Gründe, warum ein Payload nicht gelesen werden kann. Die Oberfläche übersetzt sie. */
enum class PayloadError {
    WRONG_FORMAT,
    WRONG_VERSION,
    MISSING_FIELD,
    INVALID_SSID,
    INVALID_PASSPHRASE,
    INVALID_HOST,
    INVALID_PORT,
    INVALID_TOKEN,
}
