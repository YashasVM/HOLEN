package com.yashasvm.holen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection

class DirectDownloaderResponseTest {
    @Test
    fun freshDownloadsRejectUnsolicitedPartialResponses() {
        assertTrue(
            DirectDownloader.isAcceptedTransferResponse(
                attemptedResume = false,
                responseCode = HttpURLConnection.HTTP_OK,
            ),
        )
        assertFalse(
            DirectDownloader.isAcceptedTransferResponse(
                attemptedResume = false,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
            ),
        )
    }

    @Test
    fun validPartialResumeIsStillAccepted() {
        assertTrue(
            DirectDownloader.isAcceptedTransferResponse(
                attemptedResume = true,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
            ),
        )
        assertFalse(
            DirectDownloader.shouldRestartAfterResume(
                existingBytes = 512,
                attemptedResume = true,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
                contentRange = "bytes 512-1023/1024",
            ),
        )
    }

    @Test
    fun completed416RangeNeverPublishesUnverifiedStagingBytes() {
        assertTrue(
            DirectDownloader.shouldRestartAfterResume(
                existingBytes = 512,
                attemptedResume = true,
                responseCode = 416,
                contentRange = "bytes */512",
            ),
        )
        assertFalse(
            DirectDownloader.isAcceptedTransferResponse(
                attemptedResume = true,
                responseCode = 416,
            ),
        )
        assertTrue(
            DirectDownloader.shouldRestartAfterResume(
                existingBytes = 512,
                attemptedResume = true,
                responseCode = 416,
                contentRange = null,
            ),
        )
    }

    @Test
    fun mismatchedPartialRangeAlsoRestarts() {
        assertTrue(
            DirectDownloader.shouldRestartAfterResume(
                existingBytes = 512,
                attemptedResume = true,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
                contentRange = "bytes 0-511/1024",
            ),
        )
    }
}
