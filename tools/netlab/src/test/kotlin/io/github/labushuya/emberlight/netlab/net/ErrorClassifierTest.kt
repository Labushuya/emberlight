package io.github.labushuya.emberlight.netlab.net

import io.github.labushuya.emberlight.netlab.report.ErrorKind
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorClassifierTest {
    @Test
    fun mapsCommonExceptionTypes() {
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.classify(SocketTimeoutException("read timed out")))
        assertEquals(ErrorKind.NO_ROUTE, ErrorClassifier.classify(NoRouteToHostException("x")))
        assertEquals(ErrorKind.UNKNOWN_HOST, ErrorClassifier.classify(UnknownHostException("x")))
        assertEquals(ErrorKind.IO, ErrorClassifier.classify(IOException("broken pipe")))
        assertEquals(ErrorKind.OTHER, ErrorClassifier.classify(IllegalStateException("x")))
    }

    @Test
    fun connectExceptionDistinguishesRefusedUnreachableAndTimeout() {
        assertEquals(ErrorKind.REFUSED, ErrorClassifier.classify(ConnectException("Connection refused")))
        assertEquals(
            ErrorKind.NO_ROUTE,
            ErrorClassifier.classify(ConnectException("connect failed: ENETUNREACH (Network is unreachable)")),
        )
        assertEquals(ErrorKind.TIMEOUT, ErrorClassifier.classify(ConnectException("failed to connect: timed out")))
        assertEquals(ErrorKind.REFUSED, ErrorClassifier.classify(ConnectException()))
    }
}
