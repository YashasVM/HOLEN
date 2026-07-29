package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection

class CoreLogicTest {
    @Test
    fun httpsValidationAcceptsOnlyPublicHttpsUrls() {
        assertEquals("https://example.com/a?b=1", validateHttpsUrl(" https://example.com/a?b=1 "))
        listOf(
            "http://example.com",
            "file:///tmp/a",
            "content://provider/a",
            "javascript:alert(1)",
            "https://user:pass@example.com/file",
            "not a url",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { validateHttpsUrl(value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateHttpsUrl("https://example.com/" + "a".repeat(4_096))
        }
    }

    @Test
    fun sourceClassificationFollowsAttachmentAndMimeRules() {
        assertTrue(SourceAnalyzer.isDirectFile("attachment; filename=a.zip", "text/html"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "application/pdf"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "video/mp4"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "text/html; charset=utf-8"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "application/xhtml+xml"))
        assertFalse(SourceAnalyzer.isDirectFile(null, null))
    }

    @Test
    fun fileNamesCannotTraverseAndDestinationsNeverOverwrite() {
        assertEquals("secret_.mp4", sanitizeFileName("../../secret?.mp4"))
        assertEquals("download", sanitizeFileName(".."))
        assertEquals(
            "clip (3).mp4",
            OutputStore.destinationName(
                "clip.mp4",
                setOf("clip.mp4", "clip (1).mp4", "clip (2).mp4"),
            ),
        )
    }

    @Test
    fun contentDispositionSupportsUtf8AndQuotedNames() {
        assertEquals(
            "hello world.zip",
            DirectDownloader.fileNameFromDisposition(
                "attachment; filename*=UTF-8''hello%20world.zip",
            ),
        )
        assertEquals(
            "report.pdf",
            DirectDownloader.fileNameFromDisposition("attachment; filename=\"report.pdf\""),
        )
        assertNull(DirectDownloader.fileNameFromDisposition(null))
    }

    @Test
    fun rangeResumeAppendsOnlyWhenServerConfirmsPartialContent() {
        assertTrue(DirectDownloader.shouldAppend(100, HttpURLConnection.HTTP_PARTIAL))
        assertFalse(DirectDownloader.shouldAppend(100, HttpURLConnection.HTTP_OK))
        assertFalse(DirectDownloader.shouldAppend(0, HttpURLConnection.HTTP_PARTIAL))
    }

    @Test
    fun everyMediaFormatHasLockedArguments() {
        assertEquals(
            listOf(
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
            ),
            YtDlpEngine.downloadArguments(DownloadFormat.BEST_MP4),
        )
        assertEquals(
            listOf(
                "-f",
                "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]",
                "--merge-output-format", "mp4",
            ),
            YtDlpEngine.downloadArguments(DownloadFormat.MP4_1080),
        )
        assertEquals(
            listOf(
                "-f",
                "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]",
                "--merge-output-format", "mp4",
            ),
            YtDlpEngine.downloadArguments(DownloadFormat.MP4_720),
        )
        assertEquals(
            listOf(
                "-f", "bestaudio[ext=m4a]/bestaudio",
                "--extract-audio", "--audio-format", "m4a",
            ),
            YtDlpEngine.downloadArguments(DownloadFormat.AUDIO_M4A),
        )
        assertEquals(
            listOf(
                "-f", "bestaudio", "--extract-audio", "--audio-format", "mp3",
                "--audio-quality", "0",
            ),
            YtDlpEngine.downloadArguments(DownloadFormat.AUDIO_MP3),
        )
        assertThrows(IllegalStateException::class.java) {
            YtDlpEngine.downloadArguments(DownloadFormat.ORIGINAL)
        }
    }

    @Test
    fun progressParsesPercentSpeedAndEta() {
        val result = parseTransferLine(
            "[download]  42.5% of 10.00MiB at 2.50MiB/s ETA 00:03",
        )!!
        assertEquals(42, result.percent)
        assertEquals(2_621_440L, result.speedBytesPerSecond)
        assertEquals(3L, result.etaSeconds)
    }

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
    }
}
