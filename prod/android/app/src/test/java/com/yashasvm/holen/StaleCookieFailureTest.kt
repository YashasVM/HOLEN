package com.yashasvm.holen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleCookieFailureTest {
    @Test
    fun rotatedYoutubeCookiesTellUserToRefreshAuthentication() {
        val result = friendlyFailure(
            IllegalStateException(
                "WARNING: [youtube] The provided YouTube account cookies are no longer valid. " +
                    "They have likely been rotated in the browser as a security measure.",
            ),
        )

        assertTrue(result.contains("no longer valid"))
        assertTrue(result.contains("Export fresh cookies"))
        assertTrue(result.contains("replace them in Settings"))
        assertFalse(result.contains("network transfer failed", ignoreCase = true))
    }

    @Test
    fun staleCookieEvidenceTakesPriorityOverBotCheckAdvice() {
        val result = friendlyFailure(
            IllegalStateException(
                "WARNING: [youtube] The provided YouTube account cookies are no longer valid. " +
                    "ERROR: [youtube] abc123: Sign in to confirm you're not a bot.",
            ),
        )

        assertTrue(result.contains("Export fresh cookies"))
        assertFalse(result.contains("Wait a little"))
    }
}
