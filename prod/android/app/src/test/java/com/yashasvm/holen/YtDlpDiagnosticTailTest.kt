package com.yashasvm.holen

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class YtDlpDiagnosticTailTest {
    @Test
    fun `tail keeps diagnostics and drops progress noise`() {
        val tail = YtDlpDiagnosticTail(maxChars = 128)

        tail.add("download:$PROGRESS_MARKER 42.0%|42|100|NA|1MiB/s|3")
        tail.add("WARNING: account cookies are no longer valid")
        tail.add("ERROR: Sign in to confirm you're not a bot")

        val diagnostics = tail.snapshot()
        assertFalse(diagnostics.contains(PROGRESS_MARKER))
        assertTrue(diagnostics.contains("cookies are no longer valid"))
        assertTrue(diagnostics.contains("confirm you're not a bot"))
    }

    @Test
    fun `failure gains callback diagnostics when wrapper error is empty`() {
        val original = IOException("")
        val result = withYtDlpDiagnostics(
            original,
            "ERROR: HTTP Error 429: Too Many Requests",
            cancelled = false,
        )

        assertTrue(result is IOException)
        assertEquals("ERROR: HTTP Error 429: Too Many Requests", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun `preserved stale cookie diagnostic reaches friendly failure classification`() {
        val result = withYtDlpDiagnostics(
            IOException(""),
            "WARNING: The account cookies are no longer valid. They may have been rotated.\nERROR: Sign in to confirm you're not a bot",
            cancelled = false,
        )

        assertEquals(
            "The saved account cookies are no longer valid. Export fresh cookies from a signed-in browser session, replace them in Settings, then retry.",
            friendlyFailure(result),
        )
    }

    @Test
    fun `preserved rate limit diagnostic reaches friendly failure classification`() {
        val result = withYtDlpDiagnostics(
            IOException(""),
            "ERROR: HTTP Error 429: Too Many Requests",
            cancelled = false,
        )

        assertEquals(
            "The source is rate-limiting downloads. Wait before retrying; repeated retries can extend the limit.",
            friendlyFailure(result),
        )
    }

    @Test
    fun `preserved authentication diagnostic reaches friendly failure classification`() {
        val result = withYtDlpDiagnostics(
            IOException(""),
            "ERROR: This video is private. Please sign in",
            cancelled = false,
        )

        assertEquals(
            "This source needs a signed-in account. Add fresh cookies from an account permitted to access it, then retry.",
            friendlyFailure(result),
        )
    }

    @Test
    fun `cancellation is preserved instead of becoming a diagnostic failure`() {
        val original = IOException("wrapper failed")
        val result = withYtDlpDiagnostics(
            original,
            "ERROR: extractor failed",
            cancelled = true,
        )

        assertTrue(result is CancellationException)
        assertSame(original, result.cause)
    }

    @Test
    fun `existing coroutine cancellation passes through unchanged`() {
        val original = CancellationException("cancelled")
        val result = withYtDlpDiagnostics(original, "ERROR: extractor failed", cancelled = false)

        assertSame(original, result)
    }
}
