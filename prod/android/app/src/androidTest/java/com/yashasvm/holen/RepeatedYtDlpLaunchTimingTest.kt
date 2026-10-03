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
 * Measures two back-to-back yt-dlp process launches after the bundled runtime is initialized.
 * This separates persistent per-process startup overhead from one-time runtime extraction/setup.
 */
@RunWith(AndroidJUnit4::class)
class RepeatedYtDlpLaunchTimingTest {
    @Test
    fun repeatedProcessLaunchCostIsMeasured() {
        assumeTrue(
            "Repeated yt-dlp launch timing runs only when explicitly requested by CI.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)

        val firstMs = measureVersionProbe("repeat-launch-first")
        val repeatMs = measureVersionProbe("repeat-launch-second")

        val report = buildString {
            appendLine("HOLEN repeated yt-dlp process launch timing")
            appendLine("process_launch_first_ms=$firstMs")
            appendLine("process_launch_repeat_ms=$repeatMs")
        }
        Log.i(REPORT_TAG, report.trim().replace('\n', ' '))
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("first yt-dlp process launch must complete", firstMs >= 0L)
        assertTrue("repeated yt-dlp process launch must complete", repeatMs >= 0L)
    }

    private fun measureVersionProbe(processId: String): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val response = YoutubeDL.execute(
            YoutubeDLRequest(emptyList()).addOption("--version"),
            processId,
            null,
        )
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        check(response.out.isNotBlank()) { "yt-dlp version probe returned no output" }
        return elapsed
    }

    private companion object {
        const val ENABLE_ARGUMENT = "holenRepeatedLaunchTiming"
        const val REPORT_FILE = "repeated-yt-dlp-launch-timing.txt"
        const val REPORT_TAG = "HOLENRepeatLaunch"
    }
}
