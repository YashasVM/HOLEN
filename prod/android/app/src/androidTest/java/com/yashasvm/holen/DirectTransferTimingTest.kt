package com.yashasvm.holen

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deterministic localhost transfer probe for the Android direct-download copy path. This does not
 * claim public-network throughput; it isolates Java stream, Range-resume, and private-file costs
 * with the same 256 KiB copy buffer used by DirectDownloader.
 */
@RunWith(AndroidJUnit4::class)
class DirectTransferTimingTest {
    @Test
    fun freshAndRangeResumeTransfersAreMeasured() {
        assumeTrue(
            "Direct transfer timing runs only when explicitly requested by CI.",
            InstrumentationRegistry.getArguments().getString(ENABLE_ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "direct-transfer-timing").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            LocalRangeServer(TOTAL_BYTES).use { server ->
                val fresh = File(directory, "fresh.part")
                val freshMs = download(server.url, fresh, 0L)
                assertEquals(TOTAL_BYTES, fresh.length())

                val resumed = File(directory, "resume.part")
                FileOutputStream(resumed).use { output ->
                    writePattern(output, RESUME_OFFSET)
                    output.fd.sync()
                }
                val resumeMs = download(server.url, resumed, RESUME_OFFSET)
                assertEquals(TOTAL_BYTES, resumed.length())

                val report = buildString {
                    appendLine("HOLEN Android direct-transfer timing")
                    appendLine("transfer_bytes=$TOTAL_BYTES")
                    appendLine("transfer_fresh_ms=$freshMs")
                    appendLine("transfer_resume_offset_bytes=$RESUME_OFFSET")
                    appendLine("transfer_resume_ms=$resumeMs")
                }
                Log.i(REPORT_TAG, report.trim().replace('\n', ' '))
                File(context.cacheDir, REPORT_FILE).writeText(report)

                assertTrue("fresh localhost transfer must complete", freshMs >= 0L)
                assertTrue("Range resume transfer must complete", resumeMs >= 0L)
                assertTrue("server must observe a Range request", server.sawExpectedRange)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun download(url: String, destination: File, offset: Long): Long {
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
            BufferedInputStream(connection.inputStream, COPY_BUFFER_SIZE).use { input ->
                FileOutputStream(destination, offset > 0L).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
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
        val buffer = ByteArray(COPY_BUFFER_SIZE) { index -> (index and 0xff).toByte() }
        var remaining = bytes
        while (remaining > 0L) {
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            output.write(buffer, 0, count)
            remaining -= count
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
            if (rangeStart == RESUME_OFFSET) sawExpectedRange = true
            val bodyBytes = totalBytes - rangeStart
            socket.getOutputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                if (partial) {
                    output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                    output.write("Content-Range: bytes $rangeStart-${totalBytes - 1}/$totalBytes\r\n".toByteArray())
                } else {
                    output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                }
                output.write("Content-Type: application/octet-stream\r\n".toByteArray())
                output.write("Content-Length: $bodyBytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                val buffer = ByteArray(COPY_BUFFER_SIZE) { index -> (index and 0xff).toByte() }
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
        const val ENABLE_ARGUMENT = "holenTransferTiming"
        const val REPORT_FILE = "direct-transfer-timing.txt"
        const val REPORT_TAG = "HOLENTransferTiming"
        const val COPY_BUFFER_SIZE = 256 * 1024
        const val TOTAL_BYTES = 64L * 1024L * 1024L
        const val RESUME_OFFSET = TOTAL_BYTES / 2
    }
}
