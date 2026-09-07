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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Aria2ResumeInstrumentedTest {
    @Test
    fun freshYtDlpInvocationResumesInterruptedAria2TransferWithRangeRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        Aria2c.init(context)
        val outputDir = File(context.cacheDir, "aria2-resume-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            ResumeMediaServer().use { server ->
                val firstFailure = runCatching {
                    executeDownload(server.mediaUrl, outputDir, "$PROCESS_ID-first")
                }.exceptionOrNull()
                assertNotNull("The first transfer must fail after the server truncates its body", firstFailure)

                val response = executeDownload(server.mediaUrl, outputDir, "$PROCESS_ID-second")
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
                        ?.let { it in 1 until MEDIA_BYTES.size.toLong() } == true
                }
                assertNotNull(
                    "Second aria2 invocation must request a non-zero byte range instead of restarting from zero",
                    resumedRange,
                )
                assertTrue("The server should have seen both yt-dlp probes and transfer attempts", server.totalRequests >= 4)
            }
        } finally {
            YoutubeDL.destroyProcessById("$PROCESS_ID-first")
            YoutubeDL.destroyProcessById("$PROCESS_ID-second")
            outputDir.deleteRecursively()
        }
    }

    private fun executeDownload(url: String, outputDir: File, processId: String): String {
        val response = YoutubeDL.execute(
            YoutubeDLRequest(url)
                .addOption("--ignore-config")
                .addOption("--continue")
                .addOption("--no-overwrites")
                .addOption("--downloader", "libaria2c.so")
                .addOption(
                    "--downloader-args",
                    "aria2c:--max-tries=1 --connect-timeout=5 --timeout=5 --split=1 --max-connection-per-server=1 --file-allocation=none",
                )
                .addOption("--no-playlist")
                .addOption("--output", File(outputDir, "resume.%(ext)s").absolutePath),
            processId,
            null,
        )
        return response.out
    }

    private class ResumeMediaServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val requestCount = AtomicInteger(0)
        private val transferCount = AtomicInteger(0)
        val rangeHeaders = CopyOnWriteArrayList<String>()
        @Volatile private var closed = false
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
        val totalRequests: Int
            get() = requestCount.get()

        private fun serve(socket: Socket) {
            val request = readRequest(socket)
            if (request.path != "/media.mp4") {
                respond(socket, 404, ByteArray(0))
                return
            }

            requestCount.incrementAndGet()
            val range = request.headers["range"]
            if (range != null) rangeHeaders += range

            // yt-dlp's generic extractor probes the direct media URL before handing it to aria2.
            // Those requests do not carry Range; aria2 transfer requests do.
            if (range == null) {
                respond(socket, 200, MEDIA_BYTES)
                return
            }

            val transferNumber = transferCount.incrementAndGet()
            val start = range.removePrefix("bytes=").substringBefore('-').toIntOrNull() ?: 0
            if (transferNumber == 1 && start == 0) {
                respondTruncated(socket)
                return
            }

            val body = MEDIA_BYTES.copyOfRange(start.coerceAtMost(MEDIA_BYTES.size), MEDIA_BYTES.size)
            respondPartial(socket, start, body)
        }

        private fun respondTruncated(socket: Socket) {
            val partial = MEDIA_BYTES.copyOfRange(0, INTERRUPT_AFTER_BYTES)
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                output.write("Content-Type: video/mp4\r\n".toByteArray())
                output.write("Content-Length: ${MEDIA_BYTES.size}\r\n".toByteArray())
                output.write("Accept-Ranges: bytes\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(partial)
                output.flush()
            }
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
        const val INTERRUPT_AFTER_BYTES = 96 * 1024
        val MEDIA_BYTES = ByteArray(512 * 1024) { index -> ((index * 31) and 0xff).toByte() }

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
