package com.yashasvm.holen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleExtractorRecoveryTest {
    @Test
    fun highConfidenceExtractorBreakageQualifies() {
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
    fun noFormatsFailureDoesNotQualifyEvenWithBugGuidance() {
        assertFalse(isStaleExtractorCandidate("ERROR: No video formats found"))
        assertFalse(
            isStaleExtractorCandidate(
                "ERROR: [youtube] abc: No video formats found!; please report this issue on https://github.com/yt-dlp/yt-dlp/issues",
            ),
        )
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

    @Test
    fun recoveryRunsMaintenanceAfterFailedAttemptAndRetriesOnce() = runBlocking {
        val events = mutableListOf<String>()
        var attempts = 0

        val result = runWithSingleStaleExtractorRecovery(
            operation = {
                attempts++
                events += "operation-$attempts"
                if (attempts == 1) {
                    throw IllegalStateException(
                        "Unable to extract player response; please report this issue",
                    )
                }
                "ok"
            },
            refresh = { events += "refresh" },
        )

        assertEquals("ok", result)
        assertEquals(listOf("operation-1", "refresh", "operation-2"), events)
        assertEquals(2, attempts)
    }

    @Test
    fun nonCandidateFailureDoesNotRefreshOrRetry() = runBlocking {
        val expected = IllegalStateException("HTTP Error 403")
        var refreshes = 0
        var attempts = 0

        val thrown = runCatching {
            runWithSingleStaleExtractorRecovery(
                operation = {
                    attempts++
                    throw expected
                },
                refresh = { refreshes++ },
            )
        }.exceptionOrNull()

        assertSame(expected, thrown)
        assertEquals(1, attempts)
        assertEquals(0, refreshes)
    }

    @Test
    fun failedRefreshPreservesOriginalExtractorFailure() = runBlocking {
        val original = IllegalStateException(
            "Unable to extract player response; please report this issue",
        )
        val refreshFailure = IllegalStateException("offline")

        val thrown = runCatching {
            runWithSingleStaleExtractorRecovery(
                operation = { throw original },
                refresh = { throw refreshFailure },
            )
        }.exceptionOrNull()

        assertSame(original, thrown)
        assertEquals(listOf(refreshFailure), thrown?.suppressed?.toList())
    }

    @Test
    fun cancellationNeverTriggersRecovery() = runBlocking {
        var refreshes = 0
        val cancellation = CancellationException("cancelled")

        val thrown = runCatching {
            runWithSingleStaleExtractorRecovery(
                operation = { throw cancellation },
                refresh = { refreshes++ },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(0, refreshes)
    }
}
