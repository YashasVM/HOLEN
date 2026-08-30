package com.yashasvm.holen

import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDownloadRetryPolicyTest {
    @Test
    fun retriesTransientTransportAndServerFailures() {
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("socket reset"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("Network response 408"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("Network response 500"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("Network response 502"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("Network response 503"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("Network response 504"), 0))
    }

    @Test
    fun doesNotRetryPermanentHttpRateLimitOrSecurityFailures() {
        listOf(401, 403, 404, 410, 429, 501).forEach { status ->
            assertFalse(
                "HTTP $status should not be automatically retried",
                DirectDownloadRetryPolicy.shouldRetry(IOException("Network response $status"), 0),
            )
        }
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(SSLHandshakeException("certificate"), 0))
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(ProtocolException("bad response"), 0))
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(IOException("Too many redirects."), 0))
        assertFalse(
            DirectDownloadRetryPolicy.shouldRetry(
                IOException("Redirect response had no destination."),
                0,
            ),
        )
    }

    @Test
    fun retryBudgetAndBackoffAreBounded() {
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("reset"), 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(IOException("reset"), 1))
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(IOException("reset"), 2))
        assertEquals(1_000L, DirectDownloadRetryPolicy.backoffMillis(0))
        assertEquals(2_000L, DirectDownloadRetryPolicy.backoffMillis(1))
    }
}
