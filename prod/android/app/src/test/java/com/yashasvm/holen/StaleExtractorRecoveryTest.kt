package com.yashasvm.holen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleExtractorRecoveryTest {
    @Test
    fun highConfidenceExtractorBreakageQualifies() {
        assertTrue(
            isStaleExtractorCandidate(
                "ERROR: [youtube] abc: No video formats found!; please report this issue on https://github.com/yt-dlp/yt-dlp/issues",
            ),
        )
        assertTrue(
            isStaleExtractorCandidate(
                "ERROR: [site] abc: Unable to extract player response; please report this issue",
            ),
        )
        assertTrue(
            isStaleExtractorCandidate(
                IllegalStateException(
                    "ERROR: [site] abc: Failed to extract embedded data; please report this issue",
                ),
            ),
        )
    }

    @Test
    fun genericNoFormatsFailureDoesNotQualifyWithoutUpstreamBugGuidance() {
        assertFalse(isStaleExtractorCandidate("ERROR: No video formats found"))
    }

    @Test
    fun accessAndAuthenticationFailuresNeverQualify() {
        val messages = listOf(
            "No video formats found; please report this issue; sign in required",
            "Unable to extract player response; please report this issue; HTTP Error 401",
            "Unable to extract player response; please report this issue; HTTP Error 403",
            "Unable to extract player response; please report this issue; HTTP Error 429",
            "Unable to extract player response; please report this issue; DRM protected",
            "Unable to extract player response; please report this issue; confirm you're not a bot",
            "Unable to extract player response; please report this issue; age verification required",
            "Unable to extract player response; please report this issue; not available in your country",
        )
        messages.forEach { assertFalse(it, isStaleExtractorCandidate(it)) }
    }

    @Test
    fun transportAndServerFailuresNeverQualify() {
        val messages = listOf(
            "Unable to extract player response; please report this issue; HTTP Error 503",
            "Unable to extract player response; please report this issue; connection reset",
            "Unable to extract player response; please report this issue; unable to download webpage",
            "Unable to extract player response; please report this issue; request timed out",
            "Unable to extract player response; please report this issue; fragment not found",
            "Unable to extract player response; please report this issue; downloaded file is empty",
        )
        messages.forEach { assertFalse(it, isStaleExtractorCandidate(it)) }
    }

    @Test
    fun mediaStorageAndPostProcessingFailuresNeverQualify() {
        val messages = listOf(
            "No video formats found; please report this issue; video unavailable",
            "No video formats found; please report this issue; requested format is not available",
            "Unable to extract player response; please report this issue; unsupported URL",
            "Unable to extract player response; please report this issue; ENOSPC",
            "Unable to extract player response; please report this issue; permission denied",
            "Unable to extract player response; please report this issue; FFmpeg error",
            "Unable to extract player response; please report this issue; post-processing failed",
        )
        messages.forEach { assertFalse(it, isStaleExtractorCandidate(it)) }
    }
}
