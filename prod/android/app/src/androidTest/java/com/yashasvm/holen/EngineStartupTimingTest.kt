package com.yashasvm.holen

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures cold wrapper extraction/initialization on the CI emulator without changing
 * production startup behavior. The workflow prints the generated report so optimization
 * decisions can be based on phase timings rather than guesses.
 */
@RunWith(AndroidJUnit4::class)
class EngineStartupTimingTest {
    @Test
    fun coldStartupPhasesAreMeasured() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetBundledRuntime(context)

        val youtubeDlMs = elapsedMs { YoutubeDL.init(context) }
        val ffmpegMs = elapsedMs { FFmpeg.init(context) }
        val aria2cMs = elapsedMs { Aria2c.init(context) }
        val totalMs = youtubeDlMs + ffmpegMs + aria2cMs

        val report = buildString {
            appendLine("HOLEN cold media-engine startup timing")
            appendLine("youtube_dl_ms=$youtubeDlMs")
            appendLine("ffmpeg_ms=$ffmpegMs")
            appendLine("aria2c_ms=$aria2cMs")
            appendLine("total_ms=$totalMs")
        }
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("yt-dlp initialization must complete", youtubeDlMs >= 0L)
        assertTrue("FFmpeg initialization must complete", ffmpegMs >= 0L)
        assertTrue("aria2c initialization must complete", aria2cMs >= 0L)
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
        const val REPORT_FILE = "engine-startup-timing.txt"
    }
}
