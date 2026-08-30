package com.yashasvm.holen

import android.content.Intent
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test
    fun jobTransitionsProtectTerminalFiles() {
        assertTrue(JobStatus.QUEUED.canTransitionTo(JobStatus.RUNNING))
        assertTrue(JobStatus.RUNNING.canTransitionTo(JobStatus.FINALIZING))
        assertTrue(JobStatus.FINALIZING.canTransitionTo(JobStatus.COMPLETED))
        assertTrue(JobStatus.FAILED.canTransitionTo(JobStatus.QUEUED))
        assertFalse(JobStatus.COMPLETED.canTransitionTo(JobStatus.QUEUED))
    }

    @Test
    fun failuresAreShortAndActionable() {
        assertTrue(friendlyFailure(IOException("network reset")).contains("Retry"))
        assertTrue(friendlyFailure(Exception("DRM protected")).contains("DRM"))
        assertTrue(friendlyFailure(Exception("login required")).contains("account"))
        assertTrue(friendlyFailure(Exception("permission denied")).contains("folder"))
        assertTrue(friendlyFailure(Exception("engine failed to initialize")).contains("engine"))
        assertEquals(
            "extractor engine returned an unexpected response",
            friendlyFailure(Exception("extractor engine returned an unexpected response")),
        )
        assertTrue(friendlyFailure(IOException("Network response 404")).contains("HTTP 404"))
    }

    @Test
    fun webpageErrorsAreNotMisclassifiedAsAgeRestricted() {
        val webpageFailure = friendlyFailure(Exception("Unable to download webpage: HTTP Error 403"))
        assertTrue(webpageFailure.contains("HTTP 403"))
        assertTrue(webpageFailure.contains("access", ignoreCase = true))
        assertFalse(webpageFailure.contains("age", ignoreCase = true))
        assertTrue(friendlyFailure(Exception("age-restricted video")).contains("age", ignoreCase = true))
        assertTrue(friendlyFailure(Exception("login required")).contains("account", ignoreCase = true))
        assertTrue(friendlyFailure(Exception("confirm you're not a bot")).contains("bot", ignoreCase = true))
    }

    @Test
    fun quickAnalysisHasASmallPlaylistLimitAndDeadline() {
        assertEquals(3, YtDlpEngine.QUICK_PLAYLIST_PREVIEW_LIMIT)
        assertTrue(YtDlpEngine.QUICK_ANALYSIS_TIMEOUT_MS in 1_000L..15_000L)
        assertTrue(YtDlpEngine.QUICK_PLAYLIST_PREVIEW_LIMIT < YtDlpEngine.PLAYLIST_PREVIEW_LIMIT)
    }

    @Test
    fun sharedTextExtractsTheFirstCleanHttpsUrl() {
        assertEquals(
            "https://example.com/watch?v=123",
            extractSharedUrl("Watch this: https://example.com/watch?v=123\nThanks"),
        )
        assertEquals(
            "https://example.com/file.mp4",
            extractSharedUrl("https://example.com/file.mp4)"),
        )
        assertNull(extractSharedUrl("No URL here"))
    }

    @Test
    fun sharedIntentIgnoresUnsupportedActions() {
        assertNull(extractSharedUrl(Intent(Intent.ACTION_VIEW).apply { putExtra(Intent.EXTRA_TEXT, "https://example.com") }))
    }
}
