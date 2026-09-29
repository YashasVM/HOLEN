package com.yashasvm.holen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in end-to-end probe of the real YouTube path: engine refresh, full analysis, and a media
 * download through HOLEN's own engine arguments for each video/audio format family.
 *
 * Hosted CI is often rate-limited by YouTube, so this only runs when requested with
 * `-Pandroid.testInstrumentationRunnerArguments.holenLiveYoutube=true`.
 */
@RunWith(AndroidJUnit4::class)
class LiveYoutubeDownloadInstrumentedTest {
    @Test
    fun realYoutubeVideoAnalyzesAndDownloadsInEveryFormatFamily() = runBlocking {
        assumeTrue(
            "Live YouTube probe runs only when explicitly requested.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = YtDlpEngine.get(context)

        val updateStarted = SystemClock.elapsedRealtime()
        val update = runCatching { engine.updateStable() }
        Log.i(TAG, "engine_update=${update.getOrElse { "failed: ${it.message}" }} " +
            "ms=${SystemClock.elapsedRealtime() - updateStarted}")

        val analysisStarted = SystemClock.elapsedRealtime()
        val analysis = SourceAnalyzer(engine).analyze(VIDEO_URL, AnalysisMode.FULL)
        Log.i(TAG, "analysis=${analysis::class.simpleName} ms=${SystemClock.elapsedRealtime() - analysisStarted}")
        assertTrue("A YouTube watch URL must analyze as media", analysis is SourceAnalysis.Media)
        val media = analysis as SourceAnalysis.Media

        for (format in listOf(DownloadFormat.MP4_720, DownloadFormat.BEST_MP4, DownloadFormat.AUDIO_M4A)) {
            val directory = File(context.cacheDir, "live-youtube-${format.name}").apply { deleteRecursively() }
            val job = DownloadJob(
                id = "live-${format.name}",
                sourceUrl = media.sourceUrl,
                sourceKind = SourceKind.MEDIA,
                format = format,
                title = media.title,
                thumbnailUrl = media.thumbnailUrl,
                status = JobStatus.RUNNING,
                progress = 0,
                bytesDownloaded = null,
                totalBytes = null,
                speedBytesPerSecond = null,
                etaSeconds = null,
                outputUri = null,
                fileName = null,
                mimeType = null,
                errorMessage = null,
                createdAt = 0L,
                updatedAt = 0L,
            )
            var progressEvents = 0
            val started = SystemClock.elapsedRealtime()
            val staged = engine.download(job, directory) { progressEvents++ }
            val elapsed = SystemClock.elapsedRealtime() - started
            Log.i(
                TAG,
                "format=$format file=${staged.fileName} bytes=${staged.file.length()} " +
                    "mime=${staged.mimeType} ms=$elapsed progress_events=$progressEvents",
            )
            assertTrue("$format must produce a non-trivial file", staged.file.length() > MIN_BYTES)
            val expectedExtension = if (format == DownloadFormat.AUDIO_M4A) ".m4a" else ".mp4"
            assertTrue("$format produced ${staged.fileName}", staged.fileName.endsWith(expectedExtension))
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val ENABLE_ARGUMENT = "holenLiveYoutube"
        const val TAG = "HolenLiveYoutube"
        // "Me at the zoo": 19 seconds, public, and stable since 2005.
        const val VIDEO_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        const val MIN_BYTES = 50_000L
    }
}
