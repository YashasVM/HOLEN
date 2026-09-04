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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FragmentConcurrencyInstrumentedTest {
    @Test
    fun controlledOneFourEightWorkerComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val outputRoot = File(context.cacheDir, "fragment-concurrency-probe").apply {
            deleteRecursively()
            check(mkdirs())
        }

        try {
            val results = listOf(1, 4, 8).map { workers ->
                runProbe(outputRoot, workers)
            }

            val singleWorker = results.single { it.workers == 1 }
            val fourWorkers = results.single { it.workers == 4 }
            val eightWorkers = results.single { it.workers == 8 }

            assertEquals(
                "A single fragment worker must remain serial",
                1,
                singleWorker.peakConcurrentRequests,
            )
            assertTrue(
                "Four configured workers should overlap fragment requests; peak=${fourWorkers.peakConcurrentRequests}",
                fourWorkers.peakConcurrentRequests >= 2,
            )
            assertTrue(
                "Eight configured workers should produce substantial overlap; peak=${eightWorkers.peakConcurrentRequests}",
                eightWorkers.peakConcurrentRequests >= 4,
            )
            assertTrue(
                "Configured fragment concurrency must bound observed overlap",
                results.all { it.peakConcurrentRequests <= it.workers },
            )

            println(
                "HOLEN_FRAGMENT_CONCURRENCY_COMPARISON " +
                    results.joinToString(separator = " ") { result ->
                        "workers=${result.workers},peak=${result.peakConcurrentRequests}," +
                            "fragments=${result.fragmentRequests},elapsed_ms=${result.elapsedMs}"
                    } + " delay_ms=$SEGMENT_DELAY_MS",
            )
        } finally {
            outputRoot.deleteRecursively()
        }
    }

    private fun runProbe(outputRoot: File, workers: Int): ProbeResult {
        val outputDir = File(outputRoot, "workers-$workers").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val processId = "fragment-concurrency-probe-$workers"

        try {
            DelayedFragmentHlsServer().use { server ->
                val startedAt = System.nanoTime()
                YoutubeDL.execute(
                    YoutubeDLRequest(server.playlistUrl)
                        .addOption("--ignore-config")
                        .addOption("--concurrent-fragments", workers.toString())
                        .addOption("--fragment-retries", "0")
                        .addOption("--retries", "0")
                        .addOption("--abort-on-unavailable-fragments")
                        .addOption("--socket-timeout", "5")
                        .addOption("--no-playlist")
                        .addOption("--output", File(outputDir, "probe.%(ext)s").absolutePath),
                    processId,
                    null,
                )
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                assertEquals(
                    "Every playlist fragment should be requested exactly once for workers=$workers",
                    SEGMENT_COUNT,
                    server.fragmentRequests,
                )
                assertTrue(
                    "Fragmented media should be finalized for workers=$workers",
                    outputDir.listFiles().orEmpty().any { file ->
                        file.name.startsWith("probe.") &&
                            !file.name.endsWith(".part") &&
                            !file.name.endsWith(".ytdl") &&
                            !file.name.contains(".frag")
                    },
                )

                return ProbeResult(
                    workers = workers,
                    peakConcurrentRequests = server.peakConcurrentRequests,
                    fragmentRequests = server.fragmentRequests,
                    elapsedMs = elapsedMs,
                )
            }
        } finally {
            YoutubeDL.destroyProcessById(processId)
        }
    }

    private data class ProbeResult(
        val workers: Int,
        val peakConcurrentRequests: Int,
        val fragmentRequests: Int,
        val elapsedMs: Long,
    )

    private class DelayedFragmentHlsServer : AutoCloseable {
        private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val fragmentRequestCount = AtomicInteger(0)
        private val activeRequests = AtomicInteger(0)
        private val maxActiveRequests = AtomicInteger(0)
        @Volatile private var closed = false
        private val acceptor = thread(name = "holen-fragment-concurrency-http", isDaemon = true) {
            while (!closed) {
                try {
                    val socket = server.accept()
                    executor.execute { socket.use(::serve) }
                } catch (error: SocketException) {
                    if (!closed) throw error
                }
            }
        }

        val playlistUrl: String = "http://127.0.0.1:${server.localPort}/playlist.m3u8"
        val fragmentRequests: Int
            get() = fragmentRequestCount.get()
        val peakConcurrentRequests: Int
            get() = maxActiveRequests.get()

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
            if (path == "/playlist.m3u8") {
                respond(socket, "application/vnd.apple.mpegurl", PLAYLIST.toByteArray())
                return
            }
            if (!path.startsWith("/segment-") || !path.endsWith(".ts")) {
                respondNotFound(socket)
                return
            }

            fragmentRequestCount.incrementAndGet()
            val active = activeRequests.incrementAndGet()
            maxActiveRequests.accumulateAndGet(active, ::maxOf)
            try {
                Thread.sleep(SEGMENT_DELAY_MS)
                respond(socket, "video/mp2t", SEGMENT_BYTES)
            } finally {
                activeRequests.decrementAndGet()
            }
        }

        private fun respond(socket: Socket, contentType: String, body: ByteArray) {
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        private fun respondNotFound(socket: Socket) {
            socket.getOutputStream().buffered().use { output ->
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
            }
        }

        override fun close() {
            closed = true
            server.close()
            acceptor.join(1_000)
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val SEGMENT_COUNT = 16
        const val SEGMENT_DELAY_MS = 100L
        val SEGMENT_BYTES = ByteArray(188 * 8) { index -> (index and 0xff).toByte() }
        val PLAYLIST = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:1")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            repeat(SEGMENT_COUNT) { index ->
                appendLine("#EXTINF:1.0,")
                appendLine("segment-$index.ts")
            }
            append("#EXT-X-ENDLIST")
        }
    }
}
