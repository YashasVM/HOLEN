package com.yashasvm.holen

import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyFailureTest {
    @Test
    fun directHttpFailuresAreActionableByStatusClass() {
        assertTrue(friendlyFailure(IOException("Network response 401")).contains("denied access"))
        assertTrue(friendlyFailure(IOException("Network response 403")).contains("access"))
        assertTrue(friendlyFailure(IOException("Network response 404")).contains("no longer available"))
        assertTrue(friendlyFailure(IOException("Network response 410")).contains("fresh download URL"))
        assertTrue(friendlyFailure(IOException("Network response 429")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IOException("Network response 503")).contains("temporarily unavailable"))
        assertTrue(friendlyFailure(IOException("Network response 418")).contains("HTTP 418"))
    }

    @Test
    fun extractorHttpFailuresAreClassifiedWithoutPretendingRateLimitsAreBotChecks() {
        val forbidden = friendlyFailure(IllegalStateException("ERROR: HTTP Error 403: Forbidden"))
        assertTrue(forbidden.contains("retry once without cookies"))
        assertTrue(forbidden.contains("refresh the cookies/account access"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 404: Not Found")).contains("no longer available"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 429: Too Many Requests")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 429: Too Many Requests")).contains("repeated retries"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 503: Service Unavailable")).contains("temporarily unavailable"))
    }

    @Test
    fun explicitBotChallengeStillTakesPriorityOverHttpStatus() {
        assertTrue(
            friendlyFailure(IllegalStateException("ERROR: HTTP Error 429: confirm you're not a bot"))
                .contains("bot check"),
        )
    }

    @Test
    fun requestedFormatFailureSuggestsCookieIsolationBeforeChangingQuality() {
        val result = friendlyFailure(
            IllegalStateException("ERROR: [youtube] abc123: Requested format is not available. Use --list-formats for a list of available formats"),
        )

        assertTrue(result.contains("Re-analyze"))
        assertTrue(result.contains("available format"))
        assertTrue(result.contains("retry once without cookies"))
        assertTrue(result.contains("before changing quality"))
        assertTrue(result.contains("update the media engine"))
    }

    @Test
    fun unavailableAndRegionalMediaFailuresAreActionable() {
        val unavailable = friendlyFailure(IllegalStateException("ERROR: [youtube] abc123: Video unavailable"))
        val regional = friendlyFailure(IllegalStateException("ERROR: This video is not available in your country"))

        assertTrue(unavailable.contains("currently unavailable"))
        assertTrue(unavailable.contains("Re-analyze"))
        assertTrue(regional.contains("current region"))
    }

    @Test
    fun pendingEngineResetRequiresRestartInsteadOfNetworkRetry() {
        val result = friendlyFailure(
            IOException("Media engine reset is pending. Close and reopen HOLEN before analyzing or downloading media."),
        )

        assertTrue(result.contains("Close and reopen HOLEN"))
        assertTrue(!result.contains("network transfer failed", ignoreCase = true))
    }
}
