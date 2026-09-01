package com.yashasvm.holen

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures a deliberately cold wrapper extraction/initialization cycle on CI. The destructive
 * reset is opt-in so the normal connected suite cannot delete a runtime which another test uses.
 */
@RunWith(AndroidJUnit4::class)
class EngineStartupTimingTest {
    @Test
    fun coldStartupPhasesAreMeasured() {
        assumeTrue(
            "Cold startup timing runs only when explicitly requested by CI.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetBundledRuntime(context)

        val youtubeDlMs = elapsedMs { YoutubeDL.init(context) }
        val ffmpegMs = elapsedMs { FFmpeg.init(context) }
        val aria2cMs = elapsedMs { Aria2c.init(context) }
        val processLaunchMs = elapsedMs {
            val response = YoutubeDL.execute(
                YoutubeDLRequest(emptyList()).addOption("--version"),
                "startup-timing-version",
                null,
            )
            check(response.out.isNotBlank()) { "yt-dlp version probe returned no output" }
        }
        val totalMs = youtubeDlMs + ffmpegMs + aria2cMs + processLaunchMs

        val report = buildString {
            appendLine("HOLEN cold media-engine startup timing")
            appendLine("youtube_dl_ms=$youtubeDlMs")
            appendLine("ffmpeg_ms=$ffmpegMs")
            appendLine("aria2c_ms=$aria2cMs")
            appendLine("process_launch_ms=$processLaunchMs")
            appendLine("total_ms=$totalMs")
        }
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("yt-dlp initialization must complete", youtubeDlMs >= 0L)
        assertTrue("FFmpeg initialization must complete", ffmpegMs >= 0L)
        assertTrue("aria2c initialization must complete", aria2cMs >= 0L)
        assertTrue("yt-dlp process launch must complete", processLaunchMs >= 0L)
    }

    private fun resetBundledRuntime(context: Context) {
        File(context.noBackupFilesDir, YoutubeDL.baseName).deleteRecursively()
        context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private inline fun elapsedMs(block: () -> Unit): Long {
        val startedAt = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - startedAt
    }

    private companion object {
        const val ENABLE_ARGUMENT = "holenStartupTiming"
        const val REPORT_FILE = "engine-startup-timing.txt"
    }
}
