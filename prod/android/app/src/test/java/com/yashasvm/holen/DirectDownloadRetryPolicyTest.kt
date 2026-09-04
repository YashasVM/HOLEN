package com.yashasvm.holen

import java.io.IOException
import java.net.ProtocolException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    fun retriesRateLimitOnlyWhenServerGivesBoundedRetryAfter() {
        val now = Instant.parse("2026-09-01T12:00:00Z").toEpochMilli()
        val fiveSecondsLater = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(now + 5_000L).atZone(ZoneOffset.UTC),
        )

        val delaySeconds = DirectHttpException(429, "5")
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(delaySeconds, 0, now))
        assertEquals(5_000L, DirectDownloadRetryPolicy.backoffMillis(delaySeconds, 0, now))

        val httpDate = DirectHttpException(429, fiveSecondsLater)
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(httpDate, 0, now))
        assertEquals(5_000L, DirectDownloadRetryPolicy.backoffMillis(httpDate, 0, now))

        listOf(
            DirectHttpException(429),
            DirectHttpException(429, "not-a-delay"),
            DirectHttpException(429, "31"),
            IOException("Network response 429"),
        ).forEach { error ->
            assertFalse(
                "429 without a short valid Retry-After must not be automatically retried",
                DirectDownloadRetryPolicy.shouldRetry(error, 0, now),
            )
        }
    }

    @Test
    fun doesNotRetryPermanentHttpSecurityOrStorageFailures() {
        listOf(401, 403, 404, 410, 429, 501).forEach { status ->
            assertFalse(
                "HTTP $status should not be automatically retried",
                DirectDownloadRetryPolicy.shouldRetry(IOException("Network response $status"), 0),
            )
        }
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(StorageException("disk full"), 0))
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
        val error = IOException("reset")
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(error, 0))
        assertTrue(DirectDownloadRetryPolicy.shouldRetry(error, 1))
        assertFalse(DirectDownloadRetryPolicy.shouldRetry(error, 2))
        assertEquals(1_000L, DirectDownloadRetryPolicy.backoffMillis(error, 0))
        assertEquals(2_000L, DirectDownloadRetryPolicy.backoffMillis(error, 1))
    }
}
