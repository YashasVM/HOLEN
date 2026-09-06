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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Aria2RetryInstrumentedTest {
    @Test
    fun externalDownloaderRecoversWithinConfiguredAttemptBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        Aria2c.init(context)
        val outputDir = File(context.cacheDir, "aria2-retry-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            RetryMediaServer(disconnectsAfterProbe = 2).use { server ->
                val response = YoutubeDL.execute(
                    YoutubeDLRequest(server.mediaUrl)
                        .addOption("--ignore-config")
                        .addOption("--downloader", "libaria2c.so")
                        .addOption(
                            "--downloader-args",
                            "aria2c:--max-tries=4 --connect-timeout=5 --timeout=5 --allow-overwrite=true",
                        )
                        .addOption("--no-playlist")
                        .addOption("--output", File(outputDir, "probe.%(ext)s").absolutePath),
                    PROCESS_ID,
                    null,
                )

                assertEquals(
                    "yt-dlp should probe once, then aria2 should recover after two transport disconnects",
                    4,
                    server.totalRequests,
                )
                assertEquals(
                    "aria2 should use three transfer attempts when the first two disconnect",
                    3,
                    server.downloadRequests,
                )
                assertTrue("Successful aria2 retry should return yt-dlp output", response.out.isNotBlank())
                val output = outputDir.listFiles().orEmpty().firstOrNull { it.name.startsWith("probe.") }
                assertTrue("Recovered aria2 download should be finalized", output?.isFile == true)
                assertEquals(MEDIA_BYTES.size.toLong(), output?.length())
            }
        } finally {
            outputDir.deleteRecursively()
            YoutubeDL.destroyProcessById(PROCESS_ID)
        }
    }

    @Test
    fun externalDownloaderResumesPartialTransferWithRangeRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        Aria2c.init(context)
        val outputDir = File(context.cacheDir, "aria2-resume-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            PartialTransferServer().use { server ->
                val response = YoutubeDL.execute(
                    YoutubeDLRequest(server.mediaUrl)
                        .addOption("--ignore-config")
                        .addOption("--downloader", "libaria2c.so")
                        .addOption(
                            "--downloader-args",
                            "aria2c:--max-tries=4 --connect-timeout=5 --timeout=5 --allow-overwrite=true --split=1 --max-connection-per-server=1",
                        )
                        .addOption("--no-playlist")
                        .addOption("--output", File(outputDir, "resume.%(ext)s").absolutePath),
                    RESUME_PROCESS_ID,
                    null,
                )

                val resumedFrom = server.resumeOffset
                assertNotNull("aria2 should retry the interrupted transfer with a Range request", resumedFrom)
                assertTrue("Range retry should continue after already received bytes", resumedFrom!! > 0)
                assertTrue("Successful resumed download should return yt-dlp output", response.out.isNotBlank())
                val output = outputDir.listFiles().orEmpty().firstOrNull { it.name.startsWith("resume.") }
                assertTrue("Resumed aria2 download should be finalized", output?.isFile == true)
                assertEquals(RESUME_MEDIA_BYTES.size.toLong(), output?.length())
                assertTrue(
                    "Resumed output must preserve exact media bytes",
                    output?.readBytes()?.contentEquals(RESUME_MEDIA_BYTES) == true,
                )
            }
        } finally {
            outputDir.deleteRecursively()
            YoutubeDL.destroyProcessById(RESUME_PROCESS_ID)
        }
    }

    private class RetryMediaServer(
        private val disconnectsAfterProbe: Int,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val totalRequestCount = AtomicInteger(0)
        private val downloadRequestCount = AtomicInteger(0)
        @Volatile private var closed = false
        private val worker = thread(name = "holen-aria2-retry-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use(::serve)
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val mediaUrl: String = "http://127.0.0.1:${server.localPort}/media.mp4"
        val totalRequests: Int
            get() = totalRequestCount.get()
        val downloadRequests: Int
            get() = downloadRequestCount.get()

        private fun serve(socket: Socket) {
            val request = readRequest(socket)
            if (request.path != "/media.mp4") {
                respond(socket, 404, "text/plain", ByteArray(0))
                return
            }

            val requestNumber = totalRequestCount.incrementAndGet()
            if (requestNumber == 1) {
                // yt-dlp's generic extractor probes the media URL before handing it to aria2.
                respond(socket, 200, "video/mp4", MEDIA_BYTES)
                return
            }

            val downloadAttempt = downloadRequestCount.incrementAndGet()
            if (downloadAttempt <= disconnectsAfterProbe) {
                // Close the accepted connection without an HTTP response. aria2 does not retry
                // ordinary HTTP 5xx responses with HOLEN's policy, but transport failures are
                // exactly what --max-tries is intended to recover from.
                return
            }
            respond(socket, 200, "video/mp4", MEDIA_BYTES)
        }

        override fun close() {
            closed = true
            server.close()
            worker.join(1_000)
        }
    }

    private class PartialTransferServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val requestCount = AtomicInteger(0)
        @Volatile private var closed = false
        @Volatile var resumeOffset: Int? = null
            private set
        private val worker = thread(name = "holen-aria2-resume-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use(::serve)
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val mediaUrl: String = "http://127.0.0.1:${server.localPort}/media.mp4"

        private fun serve(socket: Socket) {
            val request = readRequest(socket)
            if (request.path != "/media.mp4") {
                respond(socket, 404, "text/plain", ByteArray(0))
                return
            }

            when (requestCount.incrementAndGet()) {
                1 -> respond(socket, 200, "video/mp4", RESUME_MEDIA_BYTES)
                2 -> sendInterruptedBody(socket)
                else -> {
                    val offset = request.headers["range"]
                        ?.substringAfter("bytes=", "")
                        ?.substringBefore('-')
                        ?.toIntOrNull()
                    if (offset == null || offset <= 0 || offset >= RESUME_MEDIA_BYTES.size) {
                        respond(socket, 416, "text/plain", ByteArray(0))
                        return
                    }
                    resumeOffset = offset
                    val remaining = RESUME_MEDIA_BYTES.copyOfRange(offset, RESUME_MEDIA_BYTES.size)
                    respondPartial(socket, offset, remaining)
                }
            }
        }

        private fun sendInterruptedBody(socket: Socket) {
            val partialSize = RESUME_MEDIA_BYTES.size / 4
            val output = socket.getOutputStream().buffered()
            output.write("HTTP/1.1 200 OK\r\n".toByteArray())
            output.write("Content-Type: video/mp4\r\n".toByteArray())
            output.write("Content-Length: ${RESUME_MEDIA_BYTES.size}\r\n".toByteArray())
            output.write("Accept-Ranges: bytes\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(RESUME_MEDIA_BYTES, 0, partialSize)
            output.flush()
            socket.shutdownOutput()
        }

        private fun respondPartial(socket: Socket, offset: Int, body: ByteArray) {
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                output.write("Content-Type: video/mp4\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write(
                    "Content-Range: bytes $offset-${RESUME_MEDIA_BYTES.lastIndex}/${RESUME_MEDIA_BYTES.size}\r\n".toByteArray(),
                )
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            closed = true
            server.close()
            worker.join(1_000)
        }
    }

    private data class Request(
        val path: String,
        val headers: Map<String, String>,
    )

    private companion object {
        const val PROCESS_ID = "aria2-retry-probe"
        const val RESUME_PROCESS_ID = "aria2-resume-probe"
        val MEDIA_BYTES = ByteArray(32 * 1024) { index -> (index and 0xff).toByte() }
        val RESUME_MEDIA_BYTES = ByteArray(256 * 1024) { index -> ((index * 31) and 0xff).toByte() }

        fun readRequest(socket: Socket): Request {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return Request("", emptyMap())
            val headers = buildMap {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        put(
                            line.substring(0, separator).trim().lowercase(),
                            line.substring(separator + 1).trim(),
                        )
                    }
                }
            }
            return Request(
                path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?'),
                headers = headers,
            )
        }

        fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
            val reason = when (status) {
                200 -> "OK"
                416 -> "Range Not Satisfiable"
                else -> "Not Found"
            }
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 $status $reason\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }
    }
}
