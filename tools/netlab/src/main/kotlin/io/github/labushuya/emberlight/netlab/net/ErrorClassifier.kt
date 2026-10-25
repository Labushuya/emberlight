package io.github.labushuya.emberlight.netlab.net

import io.github.labushuya.emberlight.netlab.report.ErrorKind
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Ordnet einen Netzfehler einer Fehlerart zu (Messpunkte M2, M5). Der Rohtext der Ausnahme landet nie im Bericht.
 * Reine Funktion, damit die Zuordnung testbar bleibt.
 */
object ErrorClassifier {
    fun classify(error: Throwable): ErrorKind =
        when (error) {
            is SocketTimeoutException -> ErrorKind.TIMEOUT
            is NoRouteToHostException -> ErrorKind.NO_ROUTE
            is UnknownHostException -> ErrorKind.UNKNOWN_HOST
            is ConnectException -> connectKind(error)
            is IOException -> ErrorKind.IO
            else -> ErrorKind.OTHER
        }

    /**
     * Verbindungsabbruch: „Connection refused“ heißt, der Host ist da, aber der Port ist zu. „Network is unreachable“
     * heißt, das Netz ist nicht erreichbar (z. B. Mobilfunk als Standardnetz ohne Route zum Hotspot). Die Meldungen
     * sind sprachabhängig, deshalb die Prüfung auf beide Fassungen.
     */
    private fun connectKind(error: ConnectException): ErrorKind {
        val message = error.message.orEmpty().lowercase()
        return when {
            "unreachable" in message || "no route" in message || "nicht erreichbar" in message -> ErrorKind.NO_ROUTE
            "timed out" in message || "zeitüberschreitung" in message -> ErrorKind.TIMEOUT
            else -> ErrorKind.REFUSED
        }
    }
}
