package com.yashasvm.holen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FragmentIntegrityInstrumentedTest {
    @Test
    fun unavailableHlsFragmentAbortsWithoutFinalizingMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        val outputDir = File(context.cacheDir, "fragment-integrity-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            MissingFragmentHlsServer().use { server ->
                var failure: Throwable? = null
                try {
                    YoutubeDL.execute(
                        YoutubeDLRequest(server.playlistUrl)
                            .addOption("--ignore-config")
                            .addOption("--abort-on-unavailable-fragments")
                            .addOption("--fragment-retries", "0")
                            .addOption("--retries", "0")
                            .addOption("--socket-timeout", "5")
                            .addOption("--no-playlist")
                            .addOption("--output", File(outputDir, "probe.%(ext)s").absolutePath),
                        "fragment-integrity-probe",
                        null,
                    )
                } catch (error: Throwable) {
                    failure = error
                }

                assertTrue("yt-dlp must request the deliberately missing HLS fragment", server.missingFragmentRequested)
                assertNotNull("--abort-on-unavailable-fragments must fail the download", failure)
                val finalized = outputDir.listFiles().orEmpty().filter { file ->
                    file.name.startsWith("probe.") &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        !file.name.contains(".frag")
                }
                assertTrue(
                    "A missing fragment must not be published as completed media: ${finalized.joinToString { it.name }}",
                    finalized.isEmpty(),
                )
            }
        } finally {
            outputDir.deleteRecursively()
            YoutubeDL.destroyProcessById("fragment-integrity-probe")
        }
    }

    private class MissingFragmentHlsServer : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var closed = false
        @Volatile var missingFragmentRequested = false
            private set
        private val worker = thread(name = "holen-fragment-integrity-http", isDaemon = true) {
            while (!closed) {
                try {
                    server.accept().use(::serve)
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val playlistUrl: String = "http://127.0.0.1:${server.localPort}/playlist.m3u8"

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
                "/segment-0.ts" -> respond(socket, 200, "video/mp2t", SEGMENT_BYTES)
                "/segment-1.ts" -> {
                    missingFragmentRequested = true
                    respond(socket, 404, "text/plain", ByteArray(0))
                }
                else -> respond(socket, 404, "text/plain", ByteArray(0))
            }
        }

        private fun respond(socket: Socket, status: Int, contentType: String, body: ByteArray) {
            val reason = if (status == 200) "OK" else "Not Found"
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
        val SEGMENT_BYTES = ByteArray(188 * 4) { index -> (index and 0xff).toByte() }
        val PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:1.0,
            segment-0.ts
            #EXTINF:1.0,
            segment-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
    }
}
