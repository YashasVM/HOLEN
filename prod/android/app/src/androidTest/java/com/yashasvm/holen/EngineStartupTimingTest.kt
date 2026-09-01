package com.yashasvm.holen

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
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
        // A completed FULL-analysis prewarm leaves both download-only tools initialized.
        // Measure the wrapper-level re-entry cost that remains at a later download boundary.
        // HOLEN's own ensureInitialized path is even cheaper after prewarm because its in-memory
        // flags return before invoking these wrapper init methods again.
        val postPrewarmToolReentryMs = elapsedMs {
            FFmpeg.init(context)
            Aria2c.init(context)
        }
        val processLaunchMs = elapsedMs {
            val response = YoutubeDL.execute(
                YoutubeDLRequest(emptyList()).addOption("--version"),
                "startup-timing-version",
                null,
            )
            check(response.out.isNotBlank()) { "yt-dlp version probe returned no output" }
        }
        // Exercise a real extractor/request path without depending on the public internet. A tiny
        // localhost media response keeps network latency effectively local while still making
        // yt-dlp start Python, select the generic extractor, issue HTTP, and serialize metadata.
        val localExtractMs = LocalMediaServer().use { server ->
            elapsedMs {
                val response = YoutubeDL.execute(
                    YoutubeDLRequest(server.url)
                        .addOption("--dump-single-json")
                        .addOption("--skip-download")
                        .addOption("--no-warnings"),
                    "startup-timing-local-extract",
                    null,
                )
                check(response.out.isNotBlank()) { "localhost extractor probe returned no output" }
            }
        }
        val localExtractOverheadMs = (localExtractMs - processLaunchMs).coerceAtLeast(0L)
        val totalMs = youtubeDlMs + ffmpegMs + aria2cMs + processLaunchMs

        val report = buildString {
            appendLine("HOLEN cold media-engine startup timing")
            appendLine("youtube_dl_ms=$youtubeDlMs")
            appendLine("ffmpeg_ms=$ffmpegMs")
            appendLine("aria2c_ms=$aria2cMs")
            appendLine("post_prewarm_tool_reentry_ms=$postPrewarmToolReentryMs")
            appendLine("process_launch_ms=$processLaunchMs")
            appendLine("local_extract_ms=$localExtractMs")
            appendLine("local_extract_overhead_ms=$localExtractOverheadMs")
            appendLine("total_ms=$totalMs")
        }
        // connectedAndroidTest may clear app-private files before CI can read them back.
        // Log the same measurements so the workflow has a durable, adb-readable source.
        Log.i(REPORT_TAG, report.trim().replace('\n', ' '))
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("yt-dlp initialization must complete", youtubeDlMs >= 0L)
        assertTrue("FFmpeg initialization must complete", ffmpegMs >= 0L)
        assertTrue("aria2c initialization must complete", aria2cMs >= 0L)
        assertTrue("post-prewarm tool re-entry must complete", postPrewarmToolReentryMs >= 0L)
        assertTrue("yt-dlp process launch must complete", processLaunchMs >= 0L)
        assertTrue("localhost extraction must complete", localExtractMs >= processLaunchMs / 2)
        assertTrue("localhost extraction overhead must be non-negative", localExtractOverheadMs >= 0L)
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

    private class LocalMediaServer : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        @Volatile private var closed = false
        private val worker = thread(name = "holen-startup-timing-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val body = MINIMAL_MP4_BYTES
                        socket.getOutputStream().buffered().use { output ->
                            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                            output.write("Content-Type: video/mp4\r\n".toByteArray())
                            output.write("Content-Length: ${body.size}\r\n".toByteArray())
                            output.write("Connection: close\r\n\r\n".toByteArray())
                            output.write(body)
                            output.flush()
                        }
                    }
                } catch (_: SocketException) {
                    if (!closed) throw
                }
            }
        }

        val url: String = "http://127.0.0.1:${server.localPort}/timing-probe.mp4"

        override fun close() {
            closed = true
            server.close()
            worker.join(1_000)
        }
    }

    private companion object {
        const val ENABLE_ARGUMENT = "holenStartupTiming"
        const val REPORT_FILE = "engine-startup-timing.txt"
        const val REPORT_TAG = "HOLENStartupTiming"
        val MINIMAL_MP4_BYTES = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00,
            0x6D, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6F, 0x6D,
        )
    }
}
