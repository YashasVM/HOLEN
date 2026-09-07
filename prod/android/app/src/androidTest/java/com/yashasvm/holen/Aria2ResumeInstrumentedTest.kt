package com.yashasvm.holen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Aria2ResumeInstrumentedTest {
    @Test
    fun freshYtDlpInvocationResumesCancelledAria2TransferWithRangeRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        Aria2c.init(context)
        val outputDir = File(context.cacheDir, "aria2-resume-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            ResumeMediaServer().use { server ->
                val firstFailure = AtomicReference<Throwable?>()
                val firstFinished = CountDownLatch(1)
                thread(name = "holen-aria2-resume-first", isDaemon = true) {
                    try {
                        executeDownload(server.mediaUrl, outputDir, "$PROCESS_ID-first", "first")
                    } catch (error: Throwable) {
                        firstFailure.set(error)
                    } finally {
                        firstFinished.countDown()
                    }
                }

                assertTrue(
                    "The first aria2 request should reach the fixture",
                    server.firstAttemptStarted.await(10, TimeUnit.SECONDS),
                )
                assertTrue(
                    "Interrupted aria2 transfer must persist partial data and its control file before cancellation",
                    waitForRetainedResumeState(outputDir, 10_000),
                )

                YoutubeDL.destroyProcessById("$PROCESS_ID-first")
                server.releaseFirstAttempt()
                assertTrue(
                    "Cancelled yt-dlp/aria2 process should terminate promptly",
                    firstFinished.await(10, TimeUnit.SECONDS),
                )
                assertNotNull("Cancelling the first transfer should surface a process failure", firstFailure.get())

                val retainedData = outputDir.listFiles().orEmpty().firstOrNull {
                    it.isFile && !it.name.endsWith(".aria2") && it.length() >= PIECE_LENGTH_BYTES
                }
                assertNotNull(
                    "Cancelled aria2 transfer must retain at least one complete piece for restart-resume",
                    retainedData,
                )
                assertTrue(
                    "Cancelled aria2 transfer must retain its control file",
                    outputDir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".aria2") },
                )

                val response = executeDownload(server.mediaUrl, outputDir, "$PROCESS_ID-second", "second")
                assertTrue("Resumed yt-dlp/aria2 invocation should complete", response.isNotBlank())

                val completed = outputDir.listFiles().orEmpty().firstOrNull {
                    it.isFile && it.name == "resume.mp4"
                }
                assertNotNull("Resumed transfer should finalize resume.mp4", completed)
                assertArrayEquals(MEDIA_BYTES, completed!!.readBytes())

                val resumedRange = server.rangeHeaders.firstOrNull { header ->
                    header.removePrefix("bytes=")
                        .substringBefore('-')
                        .toLongOrNull()
                        ?.let { it in PIECE_LENGTH_BYTES until MEDIA_BYTES.size.toLong() } == true
                }
                assertNotNull(
                    "Fresh yt-dlp/aria2 invocation must request a completed non-zero piece instead of restarting",
                    resumedRange,
                )
                assertTrue("The server should see extractor probes plus both aria2 transfer attempts", server.totalRequests >= 4)
            }
        } finally {
            YoutubeDL.destroyProcessById("$PROCESS_ID-first")
            YoutubeDL.destroyProcessById("$PROCESS_ID-second")
            outputDir.deleteRecursively()
        }
    }

    private fun executeDownload(
        url: String,
        outputDir: File,
        processId: String,
        attempt: String,
    ): String {
        val response = YoutubeDL.execute(
            YoutubeDLRequest(url)
                .addOption("--ignore-config")
                .addOption("--continue")
                .addOption("--no-overwrites")
                .addOption("--downloader", "libaria2c.so")
                .addOption(
                    "--downloader-args",
                    "default:--continue=true --always-resume=true --max-tries=1 --connect-timeout=5 --timeout=5 --split=1 --max-connection-per-server=1 --piece-length=1M --file-allocation=none --auto-file-renaming=false --header=X-Holen-Aria2-Attempt:$attempt",
                )
                .addOption("--no-playlist")
                .addOption("--output", File(outputDir, "resume.%(ext)s").absolutePath),
            processId,
            null,
        )
        return response.out
    }

    private fun waitForRetainedResumeState(outputDir: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val files = outputDir.listFiles().orEmpty()
            val hasPiece = files.any {
                it.isFile && !it.name.endsWith(".aria2") && it.length() >= PIECE_LENGTH_BYTES
            }
            val hasControl = files.any { it.isFile && it.name.endsWith(".aria2") }
            if (hasPiece && hasControl) return true
            Thread.sleep(50)
        }
        return false
    }

    private class ResumeMediaServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val requestCount = AtomicInteger(0)
        private val releaseFirstResponse = CountDownLatch(1)
        val firstAttemptStarted = CountDownLatch(1)
        val rangeHeaders = CopyOnWriteArrayList<String>()
        @Volatile private var closed = false
        private val worker = thread(name = "holen-aria2-resume-http", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (error: SocketException) {
                    if (!closed) throw error
                    break
                }

                try {
                    socket.use(::serve)
                } catch (_: SocketException) {
                    // Cancellation deliberately closes the first aria2 socket. A peer reset is
                    // transport behavior under test, not a fixture failure.
                }
            }
        }

        val mediaUrl: String = "http://127.0.0.1:${server.localPort}/media.mp4"
        val totalRequests: Int
            get() = requestCount.get()

        fun releaseFirstAttempt() {
            releaseFirstResponse.countDown()
        }

        private fun serve(socket: Socket) {
            val request = readRequest(socket)
            if (request.path != "/media.mp4") {
                respond(socket, 404, ByteArray(0))
                return
            }

            requestCount.incrementAndGet()
            val range = request.headers["range"]
            if (range != null) rangeHeaders += range

            // Use a downloader-specific marker to distinguish yt-dlp extractor probes from aria2.
            // The test passes downloader args through yt-dlp's `default` bucket so this remains
            // valid when Android invokes the aria2-compatible binary as `libaria2c.so`.
            when (request.headers["x-holen-aria2-attempt"]) {
                null -> {
                    respond(socket, 200, MEDIA_BYTES)
                    return
                }
                "first" -> {
                    firstAttemptStarted.countDown()
                    respondHeldPartial(socket)
                    return
                }
            }

            if (range == null) {
                respond(socket, 200, MEDIA_BYTES)
                return
            }

            val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
            val body = MEDIA_BYTES.copyOfRange(start.coerceAtMost(MEDIA_BYTES.size), MEDIA_BYTES.size)
            respondPartial(socket, start, body)
        }

        private fun respondHeldPartial(socket: Socket) {
            val partial = MEDIA_BYTES.copyOfRange(0, INTERRUPT_AFTER_BYTES)
            val output = socket.getOutputStream().buffered()
            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Content-Type: video/mp4\r\n".toByteArray())
            output.write("Content-Length: ${MEDIA_BYTES.size}\r\n".toByteArray())
            output.write("Accept-Ranges: bytes\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(partial)
            output.flush()
            releaseFirstResponse.await(15, TimeUnit.SECONDS)
        }

        private fun respondPartial(socket: Socket, start: Int, body: ByteArray) {
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                output.write("Content-Type: video/mp4\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Content-Range: bytes $start-${MEDIA_BYTES.lastIndex}/${MEDIA_BYTES.size}\r\n".toByteArray())
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            releaseFirstResponse.countDown()
            closed = true
            server.close()
            worker.join(1_000)
        }
    }

    private data class HttpRequest(
        val path: String,
        val headers: Map<String, String>,
    )

    private companion object {
        const val PROCESS_ID = "aria2-resume-probe"
        const val PIECE_LENGTH_BYTES = 1024L * 1024L
        const val INTERRUPT_AFTER_BYTES = 2 * 1024 * 1024 + 256 * 1024
        val MEDIA_BYTES = ByteArray(6 * 1024 * 1024) { index -> ((index * 31) and 0xff).toByte() }

        fun readRequest(socket: Socket): HttpRequest {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            return HttpRequest(
                path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?'),
                headers = headers,
            )
        }

        fun respond(socket: Socket, status: Int, body: ByteArray) {
            val reason = if (status == 200) "OK" else "Not Found"
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 $status $reason\r\n".toByteArray())
                output.write("Content-Type: video/mp4\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }
    }
}
