package com.yashasvm.holen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in compatibility probe for the same GitHub-hosted EJS distribution used by production
 * YouTube analysis/downloads.
 *
 * Hosted CI can be rate-limited by YouTube, so this remains explicit. On a usable network the
 * probe is intentionally strict: the first extraction must fetch the challenge solver from the
 * official yt-dlp/ejs GitHub release and populate persistent cache state, then a second extraction
 * must use that cached solver while retaining the same state. A rate-limited run is reported with
 * diagnostics but fails validation rather than being mistaken for compatibility proof.
 */
@RunWith(AndroidJUnit4::class)
class EjsRemoteComponentInstrumentedTest {
    @Test
    fun remoteEjsFirstFetchAndCachedReuseAreValidated() {
        assumeTrue(
            "EJS compatibility probe runs only when explicitly requested by CI.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)

        val probeCacheDir = File(context.cacheDir, PROBE_CACHE_DIR)
        probeCacheDir.deleteRecursively()
        val first = runProbe("ejs-first-fetch", probeCacheDir)
        val firstCache = cacheSnapshot(probeCacheDir)
        val cached = if (first.upstreamBlocked) {
            ProbeResult(
                elapsedMs = 0L,
                exitCode = BLOCKED_EXIT_CODE,
                webFetchSignal = false,
                cacheReuseSignal = false,
                upstreamBlocked = true,
                diagnostics = "skipped cached-reuse attempt because the first probe was blocked by upstream HTTP 429",
            )
        } else {
            runProbe("ejs-cached-reuse", probeCacheDir)
        }
        val cachedCache = cacheSnapshot(probeCacheDir)

        val report = buildString {
            appendLine("HOLEN Android EJS remote-component probe")
            appendLine("first_ms=${first.elapsedMs}")
            appendLine("first_exit=${first.exitCode}")
            appendLine("first_web_fetch_signal=${first.webFetchSignal}")
            appendLine("first_cache_reuse_signal=${first.cacheReuseSignal}")
            appendLine("first_upstream_blocked=${first.upstreamBlocked}")
            appendLine("first_cache_files=${firstCache.size}")
            appendLine("first_cache_bytes=${firstCache.values.sum()}")
            appendLine("cached_ms=${cached.elapsedMs}")
            appendLine("cached_exit=${cached.exitCode}")
            appendLine("cached_web_fetch_signal=${cached.webFetchSignal}")
            appendLine("cached_cache_reuse_signal=${cached.cacheReuseSignal}")
            appendLine("cached_upstream_blocked=${cached.upstreamBlocked}")
            appendLine("cached_cache_files=${cachedCache.size}")
            appendLine("cached_cache_bytes=${cachedCache.values.sum()}")
            appendLine("first_diagnostics=${first.diagnostics}")
            appendLine("cached_diagnostics=${cached.diagnostics}")
        }
        Log.i(REPORT_TAG, report.lineSequence().take(15).joinToString(" "))
        Log.i(REPORT_TAG, "HOLEN EJS first diagnostics: ${first.diagnostics}")
        Log.i(REPORT_TAG, "HOLEN EJS cached diagnostics: ${cached.diagnostics}")
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("first EJS probe must record elapsed time", first.elapsedMs >= 0L)
        assertTrue("cached EJS probe must record elapsed time", cached.elapsedMs >= 0L)
        assertFalse(
            "first EJS probe was blocked by upstream rate limiting and cannot validate compatibility",
            first.upstreamBlocked,
        )
        assertEquals("first EJS remote-component extraction must succeed", 0, first.exitCode)
        assertTrue(
            "first EJS probe must fetch the solver from the official yt-dlp/ejs GitHub release",
            first.webFetchSignal,
        )
        assertFalse("first EJS fetch must populate the configured cache", firstCache.isEmpty())
        assertTrue("first EJS cache must contain non-empty data", firstCache.values.sum() > 0L)
        assertFalse(
            "cached EJS probe was blocked by upstream rate limiting and cannot validate reuse",
            cached.upstreamBlocked,
        )
        assertEquals("second EJS extraction must succeed with retained cache state", 0, cached.exitCode)
        assertTrue(
            "second EJS extraction must report using the cached challenge solver",
            cached.cacheReuseSignal,
        )
        assertFalse("second EJS extraction must retain cache state", cachedCache.isEmpty())
        assertTrue(
            "second EJS extraction must retain at least one fetched cache artifact unchanged",
            firstCache.any { (path, size) -> size > 0L && cachedCache[path] == size },
        )
    }

    private fun runProbe(processId: String, cacheDir: File): ProbeResult {
        val request = YoutubeDLRequest(TEST_VIDEO_URL).apply {
            addOption("--ignore-config")
            addOption("--cache-dir", cacheDir.absolutePath)
            addOption("--remote-components", "ejs:github")
            addOption("--simulate")
            addOption("--no-playlist")
            addOption("--verbose")
            addOption("--print", "%(id)s")
        }
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val response = YoutubeDL.execute(request, processId, null)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            probeResult(elapsed, response.exitCode, response.err + "\n" + response.out)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            probeResult(
                elapsedMs = elapsed,
                exitCode = -1,
                diagnostics = "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
        }
    }

    private fun cacheSnapshot(cacheDir: File): Map<String, Long> {
        if (!cacheDir.exists()) return emptyMap()
        return cacheDir.walkTopDown()
            .filter(File::isFile)
            .associate { file ->
                file.relativeTo(cacheDir).path.replace(File.separatorChar, '/') to file.length()
            }
    }

    private fun probeResult(elapsedMs: Long, exitCode: Int, diagnostics: String): ProbeResult {
        val diagnosticLines = diagnostics
            .lineSequence()
            .filter { line ->
                line.contains("ejs", ignoreCase = true) ||
                    line.contains("jsc", ignoreCase = true) ||
                    line.contains("challenge", ignoreCase = true) ||
                    line.contains("cache", ignoreCase = true) ||
                    line.contains("429", ignoreCase = true) ||
                    line.contains("Too Many Requests", ignoreCase = true) ||
                    line.contains("ERROR", ignoreCase = true) ||
                    line.contains("Exception", ignoreCase = true)
            }
            .toList()
            .takeLast(8)
        return ProbeResult(
            elapsedMs = elapsedMs,
            exitCode = exitCode,
            webFetchSignal = diagnostics.contains("Downloading challenge solver", ignoreCase = true) &&
                diagnostics.contains("github.com/yt-dlp/ejs/releases/download/", ignoreCase = true),
            cacheReuseSignal = diagnostics.contains("Using challenge solver", ignoreCase = true) &&
                diagnostics.contains("source: cache", ignoreCase = true),
            upstreamBlocked = diagnostics.contains("HTTP Error 429", ignoreCase = true) ||
                diagnostics.contains("Too Many Requests", ignoreCase = true),
            diagnostics = diagnosticLines
                .joinToString(" | ")
                .replace('\n', ' ')
                .take(1200),
        )
    }

    private data class ProbeResult(
        val elapsedMs: Long,
        val exitCode: Int,
        val webFetchSignal: Boolean,
        val cacheReuseSignal: Boolean,
        val upstreamBlocked: Boolean,
        val diagnostics: String,
    )

    private companion object {
        const val ENABLE_ARGUMENT = "holenEjsRemoteProbe"
        const val PROBE_CACHE_DIR = "ejs-yt-dlp-cache"
        const val REPORT_FILE = "ejs-remote-component-probe.txt"
        const val REPORT_TAG = "HOLENEjsProbe"
        const val BLOCKED_EXIT_CODE = -2
        const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=BaW_jenozKc"
    }
}
