package com.yashasvm.holen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavascriptChallengeFailureTest {
    @Test
    fun signatureSolverFailurePointsToEngineAndEjsRecovery() {
        val result = friendlyFailure(
            IllegalStateException(
                "WARNING: [youtube] abc123: Signature solving failed: Some formats may be missing. " +
                    "Ensure you have a supported JavaScript runtime and challenge solver script distribution installed.",
            ),
        )

        assertTrue(result.contains("JavaScript challenge"))
        assertTrue(result.contains("Update the media engine"))
        assertTrue(result.contains("GitHub"))
        assertTrue(result.contains("official yt-dlp EJS solver"))
        assertFalse(result.contains("network transfer failed", ignoreCase = true))
    }

    @Test
    fun nChallengeFailureUsesTheSameActionableRecovery() {
        val result = friendlyFailure(
            IllegalStateException("WARNING: [youtube] abc123: n challenge solving failed: Some formats may be missing."),
        )

        assertTrue(result.contains("JavaScript challenge"))
        assertTrue(result.contains("Update the media engine"))
    }
}
