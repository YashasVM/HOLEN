package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.io.File

class CoreLogicTest {
    private val validCookies = """
        # Netscape HTTP Cookie File
        .example.com	TRUE	/	TRUE	1893456000	session	value
    """.trimIndent()

    @Test
    fun netscapeCookiesAreStrictlyValidated() {
        assertTrue(CookieStore.validateCookieBytes(validCookies.toByteArray()))
        assertFalse(CookieStore.validateCookieBytes("{}".toByteArray()))
        assertFalse(
            CookieStore.validateCookieBytes(
                "# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tTRUE\t0\tmissing".toByteArray(),
            ),
        )
        assertFalse(CookieStore.validateCookieBytes(ByteArray(CookieStore.MAX_BYTES + 1)))
    }

    @Test
    fun cookieArgumentsAreAddedOnlyWhenAFileExists() {
        assertTrue(CookieStore.cookieArguments(null).isEmpty())
        val file = File("private/cookies.txt")
        assertEquals(listOf("--cookies", file.absolutePath), CookieStore.cookieArguments(file))
    }

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
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
        ).forEach { address ->
            assertFalse("$address must not be treated as public", isPublicAddress(InetAddress.getByName(address)))
        }
        assertTrue(isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertThrows(IllegalArgumentException::class.java) {
            validatePublicHttpsUrl("https://127.0.0.1/private")
        }
    }

    @Test
    fun sourceClassificationFollowsAttachmentAndMimeRules() {
        assertTrue(SourceAnalyzer.isDirectFile("attachment; filename=a.zip", "text/html"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "application/pdf"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "application/vnd.rar"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "application/x-gzip"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "application/x-zip-compressed"))
        assertTrue(SourceAnalyzer.isDirectFile(null, "video/mp4"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "text/html; charset=utf-8"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "application/xhtml+xml"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "application/json"))
        assertFalse(SourceAnalyzer.isDirectFile(null, "text/plain"))
        assertFalse(SourceAnalyzer.isDirectFile(null, null))
    }

    @Test
    fun youtubeHostsSkipTheDirectFileProbe() {
        listOf(
            "youtube.com",
            "www.youtube.com",
            "m.youtube.com",
            "youtu.be",
            "www.youtube-nocookie.com",
        ).forEach { host ->
            assertTrue("$host should use the extractor immediately", SourceAnalyzer.isExtractorFirstHost(host))
        }
        listOf(
            null,
            "example.com",
            "notyoutube.com",
            "youtube.com.example.org",
        ).forEach { host ->
            assertFalse("$host must still use the safe direct-file probe", SourceAnalyzer.isExtractorFirstHost(host))
        }
    }

    @Test
    fun fileNamesCannotTraverseAndDestinationsNeverOverwrite() {
        assertEquals("secret_.mp4", sanitizeFileName("../../secret?.mp4"))
        assertEquals("download", sanitizeFileName(".."))
        assertEquals(
            "clip (3).mp4",
            destinationName(
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
        assertEquals(4_404_019L, result.bytesDownloaded)
        assertEquals(10_485_760L, result.totalBytes)
        assertEquals(2_621_440L, result.speedBytesPerSecond)
        assertEquals(3L, result.etaSeconds)
    }

    @Test
    fun progressParsesModernYtDlpSpacingAndTildeTotals() {
        val result = parseTransferLine(
            "[download]  42.5% of ~ 1,024.00MiB at  2.50MiB/s ETA 1:02:03",
        )!!
        assertEquals(42, result.percent)
        assertEquals(1_073_741_824L, result.totalBytes)
        assertEquals(2_621_440L, result.speedBytesPerSecond)
        assertEquals(3_723L, result.etaSeconds)
    }

    @Test
    fun machineProgressTemplateKeepsExactBytesWithoutHumanOutputParsing() {
        val result = parseMachineTransferLine(
            "HOLEN_PROGRESS 42.5%|4404019|10485760|NA|2621440|3",
        )!!
        assertEquals(42, result.percent)
        assertEquals(4_404_019L, result.bytesDownloaded)
        assertEquals(10_485_760L, result.totalBytes)
        assertEquals(2_621_440L, result.speedBytesPerSecond)
        assertEquals(3L, result.etaSeconds)
    }

    @Test
    fun progressSourcesMergeWithoutMovingTheBarBackward() {
        val callback = TransferProgress(52, 52L, 100L, 5L, 10L)
        val sampledFile = TransferProgress(48, 60L, null, 7L, null)
        val merged = mergeTransferProgress(callback, sampledFile)
        assertEquals(52, merged.percent)
        assertEquals(60L, merged.bytesDownloaded)
        assertEquals(100L, merged.totalBytes)
        assertEquals(7L, merged.speedBytesPerSecond)
        assertEquals(10L, merged.etaSeconds)
    }

    @Test
    fun callbackProgressNeverTurnsUnknownWrapperIntoZeroOrRegresses() {
        val previous = TransferProgress(42, 42L, 100L, 2L, 10L)
        val unknown = transferProgressFromCallback("[info] Downloading", -1f, -1L, previous)!!
        assertEquals(42, unknown.percent)
        val regressed = transferProgressFromCallback(
            "[download]  3.0% of 10.00MiB at 2.00MiB/s ETA 00:05",
            3f,
            5L,
            previous,
        )!!
        assertEquals(42, regressed.percent)
        assertEquals(10_485_760L, regressed.totalBytes)
    }

    @Test
    fun ytDlpOutputNameUsesHolenSuffixWithoutMediaId() {
        val template = YtDlpEngine.outputTemplateFor(java.io.File("downloads"))
        assertTrue(template.endsWith("%(title).160B (HOLEN).%(ext)s"))
        assertFalse(template.contains("%(id)s"))
    }

    @Test
    fun successfulEngineChecksRemainRateLimitedToOncePerWeek() {
        val now = 10_000_000_000L
        assertTrue(YtDlpEngine.isEngineCheckDue(0L, 0L, now))
        val sixDaysAgo = now - 6L * 24 * 60 * 60 * 1000
        assertFalse(YtDlpEngine.isEngineCheckDue(sixDaysAgo, sixDaysAgo, now))
        val eightDaysAgo = now - 8L * 24 * 60 * 60 * 1000
        assertTrue(YtDlpEngine.isEngineCheckDue(eightDaysAgo, eightDaysAgo, now))
    }

    @Test
    fun failedEngineChecksRetryAfterOneDayInsteadOfOneWeek() {
        val now = 10_000_000_000L
        val previousSuccess = now - 8L * 24 * 60 * 60 * 1000
        val failed23HoursAgo = now - 23L * 60 * 60 * 1000
        assertFalse(YtDlpEngine.isEngineCheckDue(failed23HoursAgo, previousSuccess, now))
        val failed25HoursAgo = now - 25L * 60 * 60 * 1000
        assertTrue(YtDlpEngine.isEngineCheckDue(failed25HoursAgo, previousSuccess, now))
    }

    @Test
    fun appUpdateVersionsAndHostsAreStrictlyValidated() {
        assertTrue(AppUpdateVersion.isNewer("v3.4.3", "3.4.2"))
        assertTrue(AppUpdateVersion.isNewer("3.5", "3.4.99"))
        assertFalse(AppUpdateVersion.isNewer("3.4.2", "3.4.2"))
        assertFalse(AppUpdateVersion.isNewer("latest", "3.4.2"))
        assertTrue(AppUpdateVersion.isAllowedGitHubHost("api.github.com"))
        assertTrue(AppUpdateVersion.isAllowedGitHubHost("objects.githubusercontent.com"))
        assertFalse(AppUpdateVersion.isAllowedGitHubHost("github.com.evil.example"))
        assertFalse(AppUpdateVersion.isAllowedGitHubHost("example.com"))
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
            "https://youtu.be/example",
            extractSharedHttps("Watch this: https://youtu.be/example)."),
        )
        assertNull(extractSharedHttps("http://example.com"))
        assertNull(extractSharedHttps("no link"))
    }
}
