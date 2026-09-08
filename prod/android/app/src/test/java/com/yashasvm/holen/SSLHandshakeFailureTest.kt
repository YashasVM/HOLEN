package com.yashasvm.holen

import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SSLHandshakeFailureTest {
    @Test
    fun sslHandshakeExceptionIsClassifiedByTypeEvenWithGenericMessage() {
        val result = friendlyFailure(SSLHandshakeException("Handshake failed"))

        assertTrue(result.contains("certificate could not be verified"))
        assertTrue(result.contains("trusted network"))
        assertFalse(result.contains("network transfer failed", ignoreCase = true))
    }
}
