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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures deliberately cold Android media-engine and local transfer/storage phases on CI. The
 * destructive runtime reset and probes are opt-in so the normal connected suite remains lightweight.
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
        val postPrewarmToolReentryMs = elapsedMs {
            FFmpeg.init(context)
            Aria2c.init(context)
        }
        var quickJsRuntimePath: String? = null
        val processLaunchMs = elapsedMs {
            val response = YoutubeDL.execute(
                YoutubeDLRequest(emptyList()).addOption("--version"),
                "startup-timing-version",
                null,
            )
            check(response.out.isNotBlank()) { "yt-dlp version probe returned no output" }
            quickJsRuntimePath = response.command
                .windowed(2)
                .firstOrNull { pair ->
                    pair[0] == "--js-runtimes" && pair[1]?.startsWith("quickjs:") == true
                }
                ?.get(1)
                ?.substringAfter("quickjs:")
        }
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
        val storageTiming = measurePrivateStorageWrite(context)
        val transferTiming = measureLocalTransfers(context)
        val totalMs = youtubeDlMs + ffmpegMs + aria2cMs + processLaunchMs
        val quickJsRuntimePresent = quickJsRuntimePath?.let(::File)?.isFile == true

        val report = buildString {
            appendLine("HOLEN Android engine/storage/transfer timing")
            appendLine("youtube_dl_ms=$youtubeDlMs")
            appendLine("ffmpeg_ms=$ffmpegMs")
            appendLine("aria2c_ms=$aria2cMs")
            appendLine("post_prewarm_tool_reentry_ms=$postPrewarmToolReentryMs")
            appendLine("process_launch_ms=$processLaunchMs")
            appendLine("quickjs_runtime_configured=${quickJsRuntimePath != null}")
            appendLine("quickjs_runtime_present=$quickJsRuntimePresent")
            appendLine("local_extract_ms=$localExtractMs")
            appendLine("local_extract_overhead_ms=$localExtractOverheadMs")
            appendLine("storage_write_bytes=$STORAGE_PROBE_BYTES")
            appendLine("storage_write_ms=${storageTiming.writeMs}")
            appendLine("storage_fsync_ms=${storageTiming.fsyncMs}")
            appendLine("transfer_bytes=$TRANSFER_PROBE_BYTES")
            appendLine("transfer_fresh_ms=${transferTiming.freshMs}")
            appendLine("transfer_resume_offset_bytes=$TRANSFER_RESUME_OFFSET")
            appendLine("transfer_resume_ms=${transferTiming.resumeMs}")
            appendLine("total_ms=$totalMs")
        }
        Log.i(REPORT_TAG, report.trim().replace('\n', ' '))
        File(context.cacheDir, REPORT_FILE).writeText(report)

        assertTrue("yt-dlp initialization must complete", youtubeDlMs >= 0L)
        assertTrue("FFmpeg initialization must complete", ffmpegMs >= 0L)
        assertTrue("aria2c initialization must complete", aria2cMs >= 0L)
        assertTrue("post-prewarm tool re-entry must complete", postPrewarmToolReentryMs >= 0L)
        assertTrue("yt-dlp process launch must complete", processLaunchMs >= 0L)
        assertTrue("youtubedl-android must configure its bundled QuickJS runtime", quickJsRuntimePath != null)
        assertTrue("configured QuickJS runtime must exist in the APK native library directory", quickJsRuntimePresent)
        assertTrue("localhost extraction must complete", localExtractMs >= processLaunchMs / 2)
        assertTrue("localhost extraction overhead must be non-negative", localExtractOverheadMs >= 0L)
        assertTrue("private-storage write must complete", storageTiming.writeMs >= 0L)
        assertTrue("private-storage fsync must complete", storageTiming.fsyncMs >= 0L)
        assertTrue("fresh localhost transfer must complete", transferTiming.freshMs >= 0L)
        assertTrue("Range resume transfer must complete", transferTiming.resumeMs >= 0L)
    }

    private fun measurePrivateStorageWrite(context: Context): StorageTiming {
        val probe = File(context.cacheDir, "direct-transfer-storage-probe.bin")
        probe.delete()
        val buffer = ByteArray(STORAGE_BUFFER_BYTES) { index -> (index and 0xff).toByte() }
        var writeMs = 0L
        var fsyncMs = 0L
        try {
            FileOutputStream(probe).use { output ->
                writeMs = elapsedMs {
                    var written = 0L
                    while (written < STORAGE_PROBE_BYTES) {
                        output.write(buffer)
                        written += buffer.size
                    }
                }
                fsyncMs = elapsedMs { output.fd.sync() }
            }
            check(probe.length() == STORAGE_PROBE_BYTES) {
                "storage probe wrote ${probe.length()} bytes, expected $STORAGE_PROBE_BYTES"
            }
            return StorageTiming(writeMs, fsyncMs)
        } finally {
            probe.delete()
        }
    }

    private fun measureLocalTransfers(context: Context): TransferTiming {
        val directory = File(context.cacheDir, "direct-transfer-timing").apply {
            deleteRecursively()
            check(mkdirs())
        }
        try {
            return LocalRangeServer(TRANSFER_PROBE_BYTES).use { server ->
                val fresh = File(directory, "fresh.part")
                val freshMs = copyLocalTransfer(server.url, fresh, 0L)
                check(fresh.length() == TRANSFER_PROBE_BYTES)

                val resumed = File(directory, "resume.part")
                FileOutputStream(resumed).use { output ->
                    writePattern(output, TRANSFER_RESUME_OFFSET)
                    output.fd.sync()
                }
                val resumeMs = copyLocalTransfer(server.url, resumed, TRANSFER_RESUME_OFFSET)
                check(resumed.length() == TRANSFER_PROBE_BYTES)
                check(server.sawExpectedRange) { "localhost transfer server did not observe Range resume" }
                TransferTiming(freshMs, resumeMs)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun copyLocalTransfer(url: String, destination: File, offset: Long): Long {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val expectedCode = if (offset > 0L) 206 else 200
            check(connection.responseCode == expectedCode) {
                "Unexpected localhost status ${connection.responseCode}, expected $expectedCode"
            }
            BufferedInputStream(connection.inputStream, STORAGE_BUFFER_BYTES).use { input ->
                FileOutputStream(destination, offset > 0L).use { output ->
                    val buffer = ByteArray(STORAGE_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            return SystemClock.elapsedRealtime() - startedAt
        } finally {
            connection.disconnect()
        }
    }

    private fun writePattern(output: FileOutputStream, bytes: Long) {
        val buffer = ByteArray(STORAGE_BUFFER_BYTES) { index -> (index and 0xff).toByte() }
        var remaining = bytes
        while (remaining > 0L) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            output.write(buffer, 0, count)
            remaining -= count
        }
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

    private data class StorageTiming(val writeMs: Long, val fsyncMs: Long)
    private data class TransferTiming(val freshMs: Long, val resumeMs: Long)

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
                } catch (error: SocketException) {
                    if (!closed) throw error
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

    private class LocalRangeServer(private val totalBytes: Long) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        @Volatile private var closed = false
        @Volatile var sawExpectedRange = false
            private set
        private val worker = thread(name = "holen-transfer-timing-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use(::serve)
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val url: String = "http://127.0.0.1:${server.localPort}/transfer.bin"

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            var rangeStart = 0L
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeStart = line.substringAfter("bytes=").substringBefore('-').trim().toLongOrNull() ?: 0L
                }
            }
            val partial = rangeStart > 0L
            if (rangeStart == TRANSFER_RESUME_OFFSET) sawExpectedRange = true
            val bodyBytes = totalBytes - rangeStart
            socket.getOutputStream().buffered(STORAGE_BUFFER_BYTES).use { output ->
                if (partial) {
                    output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                    output.write("Content-Range: bytes $rangeStart-${totalBytes - 1}/$totalBytes\r\n".toByteArray())
                } else {
                    output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                }
                output.write("Content-Type: application/octet-stream\r\n".toByteArray())
                output.write("Content-Length: $bodyBytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                val buffer = ByteArray(STORAGE_BUFFER_BYTES) { index -> (index and 0xff).toByte() }
                var remaining = bodyBytes
                while (remaining > 0L) {
                    val count = minOf(buffer.size.toLong(), remaining).toInt()
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.flush()
            }
        }

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
        const val STORAGE_BUFFER_BYTES = 256 * 1024
        const val STORAGE_PROBE_BYTES = 64L * 1024L * 1024L
        const val TRANSFER_PROBE_BYTES = 64L * 1024L * 1024L
        const val TRANSFER_RESUME_OFFSET = TRANSFER_PROBE_BYTES / 2
        val MINIMAL_MP4_BYTES = byteArrayOf(
            0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
            0x6D, 0x70, 0x34, 0x32, 0x00, 0x00, 0x00, 0x00,
            0x6D, 0x70, 0x34, 0x32, 0x69, 0x73, 0x6F, 0x6D,
        )
    }
}
