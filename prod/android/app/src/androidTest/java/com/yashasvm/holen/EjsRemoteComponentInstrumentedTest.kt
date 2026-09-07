package com.yashasvm.holen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in compatibility probe for yt-dlp's GitHub-hosted EJS distribution on Android.
 *
 * This intentionally does not enable EJS in production. It uses a dedicated yt-dlp cache, performs
 * two metadata-only YouTube extractions with ejs:github enabled, and records first-fetch versus
 * cached behavior so CI evidence can justify (or reject) a production change.
 */
@RunWith(AndroidJUnit4::class)
class EjsRemoteComponentInstrumentedTest {
    @Test
    fun remoteEjsFirstFetchAndCachedReuseAreMeasured() {
        assumeTrue(
            "EJS compatibility probe runs only when explicitly requested by CI.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)

        val probeCacheDir = File(context.cacheDir, PROBE_CACHE_DIR)
        probeCacheDir.deleteRecursively()
        val first = runProbe("ejs-first-fetch", probeCacheDir)
        val cached = runProbe("ejs-cached-reuse", probeCacheDir)

        val report = buildString {
            appendLine("HOLEN Android EJS remote-component probe")
            appendLine("first_ms=${first.elapsedMs}")
            appendLine("first_exit=${first.exitCode}")
            appendLine("first_remote_signal=${first.remoteSignal}")
            appendLine("first_cache_signal=${first.cacheSignal}")
            appendLine("cached_ms=${cached.elapsedMs}")
            appendLine("cached_exit=${cached.exitCode}")
            appendLine("cached_remote_signal=${cached.remoteSignal}")
            appendLine("cached_cache_signal=${cached.cacheSignal}")
            appendLine("first_diagnostics=${first.diagnostics}")
            appendLine("cached_diagnostics=${cached.diagnostics}")
        }
        Log.i(REPORT_TAG, report.lineSequence().take(9).joinToString(" "))
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("first EJS probe must record elapsed time", first.elapsedMs >= 0L)
        assertTrue("cached EJS probe must record elapsed time", cached.elapsedMs >= 0L)
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

    private fun probeResult(elapsedMs: Long, exitCode: Int, diagnostics: String): ProbeResult {
        val diagnosticLines = diagnostics
            .lineSequence()
            .filter { line ->
                line.contains("ejs", ignoreCase = true) ||
                    line.contains("jsc", ignoreCase = true) ||
                    line.contains("challenge", ignoreCase = true) ||
                    line.contains("cache", ignoreCase = true) ||
                    line.contains("ERROR", ignoreCase = true) ||
                    line.contains("Exception", ignoreCase = true)
            }
            .toList()
            .takeLast(8)
        return ProbeResult(
            elapsedMs = elapsedMs,
            exitCode = exitCode,
            remoteSignal = diagnostics.contains("remote component", ignoreCase = true) ||
                diagnostics.contains("ejs:github", ignoreCase = true) ||
                diagnostics.contains("Downloading challenge", ignoreCase = true),
            cacheSignal = diagnostics.contains("from cache", ignoreCase = true) ||
                diagnostics.contains("cached", ignoreCase = true) ||
                diagnostics.contains("cache hit", ignoreCase = true),
            diagnostics = diagnosticLines
                .joinToString(" | ")
                .replace('\n', ' ')
                .take(1200),
        )
    }

    private data class ProbeResult(
        val elapsedMs: Long,
        val exitCode: Int,
        val remoteSignal: Boolean,
        val cacheSignal: Boolean,
        val diagnostics: String,
    )

    private companion object {
        const val ENABLE_ARGUMENT = "holenEjsRemoteProbe"
        const val PROBE_CACHE_DIR = "ejs-yt-dlp-cache"
        const val REPORT_FILE = "ejs-remote-component-probe.txt"
        const val REPORT_TAG = "HOLENEjsProbe"
        const val TEST_VIDEO_URL = "https://www.youtube.com/watch?v=BaW_jenozKc"
    }
}
