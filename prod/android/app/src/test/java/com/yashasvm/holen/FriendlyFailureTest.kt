package com.yashasvm.holen

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyFailureTest {
    @Test
    fun directHttpFailuresAreActionableByStatusClass() {
        assertTrue(friendlyFailure(IOException("Network response 401")).contains("denied access"))
        assertTrue(friendlyFailure(IOException("Network response 403")).contains("access"))
        assertTrue(friendlyFailure(IOException("Network response 404")).contains("no longer available"))
        assertTrue(friendlyFailure(IOException("Network response 410")).contains("fresh download URL"))
        assertTrue(friendlyFailure(IOException("Network response 416")).contains("saved resume range"))
        assertTrue(friendlyFailure(IOException("Network response 416")).contains("restart the direct transfer"))
        assertTrue(friendlyFailure(IOException("Network response 429")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IOException("Network response 503")).contains("temporarily unavailable"))
        assertTrue(friendlyFailure(IOException("Network response 418")).contains("HTTP 418"))
    }

    @Test
    fun extractorHttpFailuresAreClassifiedWithoutPretendingRateLimitsAreBotChecks() {
        val forbidden = friendlyFailure(IllegalStateException("ERROR: HTTP Error 403: Forbidden"))
        val staleRange = friendlyFailure(
            IllegalStateException("ERROR: unable to download video data: HTTP Error 416: Requested range not satisfiable"),
        )
        assertTrue(forbidden.contains("retry once without cookies"))
        assertTrue(forbidden.contains("refresh the cookies/account access"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 404: Not Found")).contains("no longer available"))
        assertTrue(staleRange.contains("saved download range"))
        assertTrue(staleRange.contains("Re-analyze"))
        assertTrue(staleRange.contains("fresh download"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 429: Too Many Requests")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 429: Too Many Requests")).contains("repeated retries"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 402: Payment Required")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IllegalStateException("ERROR: HTTP Error 503: Service Unavailable")).contains("temporarily unavailable"))
    }

    @Test
    fun textualRateLimitsAreActionableWithoutAnHttpStatus() {
        val tooMany = friendlyFailure(
            IllegalStateException("ERROR: Unable to download API page: Too Many Requests"),
        )
        val explicitLimit = friendlyFailure(
            IllegalStateException("ERROR: Source rate limit exceeded; try again later"),
        )

        assertTrue(tooMany.contains("rate-limiting"))
        assertTrue(tooMany.contains("Wait before retrying"))
        assertTrue(explicitLimit.contains("rate-limiting"))
        assertFalse(tooMany.contains("network transfer failed", ignoreCase = true))
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
    fun cookieIsolationRetryIsOnlyOfferedForEligibleMediaFailures() {
        val formatFailure = friendlyFailure(
            IllegalStateException("ERROR: [youtube] abc123: Requested format is not available"),
        )
        val forbidden = friendlyFailure(IllegalStateException("ERROR: HTTP Error 403: Forbidden"))
        val ageRestricted = friendlyFailure(IllegalStateException("ERROR: This video is age-restricted"))
        val loginRequired = friendlyFailure(IllegalStateException("ERROR: Login required"))
        val membersOnly = friendlyFailure(IllegalStateException("ERROR: This video is members-only"))

        assertTrue(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                formatFailure,
                cookiesConfigured = true,
            ),
        )
        assertTrue(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                forbidden,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.DIRECT_FILE,
                JobStatus.FAILED,
                forbidden,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.QUEUED,
                forbidden,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.CANCELLED,
                forbidden,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                forbidden,
                cookiesConfigured = false,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                ageRestricted,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                loginRequired,
                cookiesConfigured = true,
            ),
        )
        assertFalse(
            shouldOfferCookieIsolationRetry(
                SourceKind.MEDIA,
                JobStatus.FAILED,
                membersOnly,
                cookiesConfigured = true,
            ),
        )
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

    @Test
    fun postProcessingFailuresDoNotMasqueradeAsNetworkErrors() {
        val ffmpegExit = friendlyFailure(IOException("ERROR: Postprocessing: ffmpeg exited with code 1"))
        val conversion = friendlyFailure(IllegalStateException("ERROR: Postprocessing: Conversion failed!"))

        assertTrue(ffmpegExit.contains("post-processing"))
        assertTrue(ffmpegExit.contains("update or reset the media engine"))
        assertFalse(ffmpegExit.contains("network transfer failed", ignoreCase = true))
        assertTrue(conversion.contains("merging or converting"))
    }

    @Test
    fun ytDlpTransientTransportFailuresAreActionableEvenWhenWrapperExceptionIsNotIo() {
        val reset = friendlyFailure(
            IllegalStateException("ERROR: [youtube] abc123: Unable to download webpage: [Errno 104] Connection reset by peer"),
        )
        val dns = friendlyFailure(
            IllegalStateException("ERROR: Unable to download API page: Temporary failure in name resolution"),
        )
        val closed = friendlyFailure(
            IllegalStateException("ERROR: Remote end closed connection without response"),
        )

        assertTrue(reset.contains("network transfer failed", ignoreCase = true))
        assertTrue(reset.contains("Retry to continue"))
        assertTrue(dns.contains("network transfer failed", ignoreCase = true))
        assertTrue(closed.contains("network transfer failed", ignoreCase = true))
    }
}
