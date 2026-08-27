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
                completedResume = false,
                attemptedResume = false,
                responseCode = HttpURLConnection.HTTP_OK,
            ),
        )
        assertFalse(
            DirectDownloader.isAcceptedTransferResponse(
                completedResume = false,
                attemptedResume = false,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
            ),
        )
    }

    @Test
    fun validResumeResponsesRemainAccepted() {
        assertTrue(
            DirectDownloader.isAcceptedTransferResponse(
                completedResume = false,
                attemptedResume = true,
                responseCode = HttpURLConnection.HTTP_PARTIAL,
            ),
        )
        assertTrue(
            DirectDownloader.isAcceptedTransferResponse(
                completedResume = true,
                attemptedResume = true,
                responseCode = 416,
            ),
        )
    }
}
