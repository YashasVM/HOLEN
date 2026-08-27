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
    fun weakEtagDoesNotEnableResume() {
        assertNull(DirectDownloader.selectResumeValidator("W/\"abc123\""))
    }

    @Test
    fun resumeIsDisabledWithoutAUsableValidator() {
        assertNull(DirectDownloader.selectResumeValidator(null))
    }

    @Test
    fun resumeStateRequiresHttpsResourceAndStrongEtag() {
        assertNotNull(
            DirectDownloader.createResumeState(
                "https://cdn.example.test/media.mp4",
                "\"abc123\"",
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
