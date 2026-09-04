package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDownloaderResumeTest {
    @Test
    fun strongEtagIsAcceptedForIfRange() {
        assertEquals(
            "\"abc123\"",
            DirectDownloader.selectResumeValidator("\"abc123\""),
        )
    }

    @Test
    fun strongEtagIsPreferredOverLastModified() {
        assertEquals(
            "\"abc123\"",
            DirectDownloader.selectResumeValidator(
                "\"abc123\"",
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:00:10 GMT",
            ),
        )
    }

    @Test
    fun weakEtagDoesNotEnableResume() {
        assertNull(DirectDownloader.selectResumeValidator("W/\"abc123\""))
        assertNull(
            DirectDownloader.selectResumeValidator(
                "W/\"abc123\"",
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:02:00 GMT",
            ),
        )
    }

    @Test
    fun lastModifiedIsAcceptedWithConservativeClockMargin() {
        assertEquals(
            "Sun, 30 Aug 2026 10:00:00 GMT",
            DirectDownloader.selectResumeValidator(
                null,
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:01:00 GMT",
            ),
        )
    }

    @Test
    fun oneSecondLastModifiedGapDoesNotEnableResumeForUnknownOriginClock() {
        assertNull(
            DirectDownloader.selectResumeValidator(
                null,
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:00:01 GMT",
            ),
        )
    }

    @Test
    fun lastModifiedIsRejectedWhenResponseDateIsNotLater() {
        assertNull(
            DirectDownloader.selectResumeValidator(
                null,
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:00:00 GMT",
            ),
        )
    }

    @Test
    fun malformedLastModifiedDoesNotEnableResume() {
        assertNull(
            DirectDownloader.selectResumeValidator(
                null,
                "not-a-date",
                "Sun, 30 Aug 2026 10:00:10 GMT",
            ),
        )
    }

    @Test
    fun resumeIsDisabledWithoutAUsableValidator() {
        assertNull(DirectDownloader.selectResumeValidator(null))
    }

    @Test
    fun resumeStateRequiresHttpsResourceAndStrongValidator() {
        assertNotNull(
            DirectDownloader.createResumeState(
                "https://cdn.example.test/media.mp4",
                "\"abc123\"",
            ),
        )
        assertNotNull(
            DirectDownloader.createResumeState(
                "https://cdn.example.test/media.mp4",
                null,
                "Sun, 30 Aug 2026 10:00:00 GMT",
                "Sun, 30 Aug 2026 10:01:00 GMT",
            ),
        )
        assertNull(
            DirectDownloader.createResumeState(
                "http://cdn.example.test/media.mp4",
                "\"abc123\"",
            ),
        )
    }

    @Test
    fun changedRedirectTargetDoesNotReuseResumeState() {
        val original = "https://cdn.example.test/a/media.mp4"
        assertTrue(DirectDownloader.resumeTargetMatches(original, original))
        assertFalse(
            DirectDownloader.resumeTargetMatches(
                original,
                "https://cdn.example.test/b/media.mp4",
            ),
        )
    }

    @Test
    fun resumeMetadataFilenameCannotReplaceCompletedPayload() {
        assertEquals(
            "download-download.resume",
            DirectDownloader.completionFileName("download.resume"),
        )
        assertEquals(
            "download-download.part",
            DirectDownloader.completionFileName("download.part"),
        )
    }
}
