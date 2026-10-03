package com.yashasvm.holen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
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
class FragmentRetryInstrumentedTest {
    @Test
    fun transientFragmentFailuresRecoverWithinAbortSafeTransferRetries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val outputDir = File(context.cacheDir, "fragment-retry-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            TransientFragmentHlsServer(failuresBeforeSuccess = 2).use { server ->
                val response = YoutubeDL.execute(
                    YoutubeDLRequest(server.playlistUrl)
                        .addOption("--ignore-config")
                        .addOption("--continue")
                        .addOption("--fragment-retries", "3")
                        .addOption("--retries", "3")
                        .addOption("--abort-on-unavailable-fragments")
                        .addOption("--socket-timeout", "5")
                        .addOption("--no-playlist")
                        .addOption("--output", File(outputDir, "probe.%(ext)s").absolutePath),
                    "fragment-retry-probe",
                    null,
                )

                assertEquals(
                    "The configured transfer retry budget should recover two transient HTTP 503 fragment responses inside one yt-dlp process",
                    3,
                    server.fragmentRequests,
                )
                assertTrue("Successful retry should return yt-dlp output", response.out.isNotBlank())
                val finalized = finalizedMedia(outputDir)
                assertNotNull("Recovered fragmented media should be finalized", finalized.firstOrNull())
                assertTrue(
                    "Recovered output should contain the served fragment",
                    finalized.first().length() >= SEGMENT_BYTES.size,
                )
            }
        } finally {
            outputDir.deleteRecursively()
            YoutubeDL.destroyProcessById("fragment-retry-probe")
        }
    }

    @Test
    fun persistentFragmentFailuresExhaustRetriesWithoutFinalizingMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val outputDir = File(context.cacheDir, "fragment-retry-exhaustion-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            TransientFragmentHlsServer(failuresBeforeSuccess = Int.MAX_VALUE).use { server ->
                var failure: Throwable? = null
                try {
                    YoutubeDL.execute(
                        YoutubeDLRequest(server.playlistUrl)
                            .addOption("--ignore-config")
                            .addOption("--continue")
                            .addOption("--fragment-retries", "3")
                            .addOption("--retries", "3")
                            .addOption("--abort-on-unavailable-fragments")
                            .addOption("--socket-timeout", "5")
                            .addOption("--no-playlist")
                            .addOption("--output", File(outputDir, "probe.%(ext)s").absolutePath),
                        "fragment-retry-exhaustion-probe",
                        null,
                    )
                } catch (error: Throwable) {
                    failure = error
                }

                assertEquals(
                    "Three configured HTTP retries should allow four total fragment attempts before exhaustion",
                    4,
                    server.fragmentRequests,
                )
                assertNotNull("Persistent HTTP 503 fragment failures must fail after the retry budget is exhausted", failure)
                val finalized = finalizedMedia(outputDir)
                assertTrue(
                    "Retry exhaustion must not publish incomplete media: ${finalized.joinToString { it.name }}",
                    finalized.isEmpty(),
                )
            }
        } finally {
            outputDir.deleteRecursively()
            YoutubeDL.destroyProcessById("fragment-retry-exhaustion-probe")
        }
    }

    private fun finalizedMedia(outputDir: File): List<File> = outputDir.listFiles().orEmpty().filter { file ->
        file.name.startsWith("probe.") &&
            !file.name.endsWith(".part") &&
            !file.name.endsWith(".ytdl") &&
            !file.name.contains(".frag")
    }

    private class TransientFragmentHlsServer(
        private val failuresBeforeSuccess: Int,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val fragmentRequestCount = AtomicInteger(0)
        @Volatile private var closed = false
        private val worker = thread(name = "holen-fragment-retry-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use(::serve)
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val playlistUrl: String = "http://127.0.0.1:${server.localPort}/playlist.m3u8"
        val fragmentRequests: Int
            get() = fragmentRequestCount.get()

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
            when (path) {
                "/playlist.m3u8" -> respond(socket, 200, "application/vnd.apple.mpegurl", PLAYLIST.toByteArray())
                "/segment-0.ts" -> {
                    val attempt = fragmentRequestCount.incrementAndGet()
                    if (attempt <= failuresBeforeSuccess) {
                        respond(socket, 503, "text/plain", "temporary failure".toByteArray())
                    } else {
                        respond(socket, 200, "video/mp2t", SEGMENT_BYTES)
                    }
                }
                else -> respond(socket, 404, "text/plain", ByteArray(0))
            }
        }

        private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
            val reason = when (status) {
                200 -> "OK"
                503 -> "Service Unavailable"
                else -> "Not Found"
            }
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 $status $reason\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
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

    private companion object {
        val SEGMENT_BYTES = ByteArray(188 * 8) { index -> (index and 0xff).toByte() }
        val PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1.0,
            segment-0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
    }
}
