package com.yashasvm.holen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectDownloaderTransferInstrumentedTest {
    @Test
    fun completed416ReplacesSameLengthStaleBytesWithFreshBody() = runBlocking {
        withStaging { directory ->
            seed(directory, "stale")
            val rejectedBody = TrackedBody("")
            val freshBody = TrackedBody("fresh")
            val offsets = mutableListOf<Long?>()
            val downloader = DirectDownloader.forResponseTest { url, offset, state ->
                assertEquals(URL, url)
                offsets.add(offset)
                when (offsets.size) {
                    1 -> {
                        assertEquals(5L, offset)
                        assertEquals("\"old\"", state?.validator)
                        response(416, rejectedBody, offset, "bytes */5")
                    }
                    2 -> {
                        assertTrue("416 response must close before restart", rejectedBody.closed)
                        assertNull(offset)
                        assertNull(state)
                        response(200, freshBody, offset)
                    }
                    else -> error("Unexpected extra request")
                }
            }

            val result = downloader.download(job(), directory) { }

            assertEquals(listOf(5L, null), offsets)
            assertEquals("fresh", result.file.readText())
            assertEquals("payload.bin", result.fileName)
            assertTrue(freshBody.closed)
            assertFalse(File(directory, "download.part").exists())
            assertFalse(File(directory, "download.resume").exists())
        }
    }

    @Test
    fun failedFreshRequestAfter416DoesNotPublishStaleBytes() = runBlocking {
        withStaging { directory ->
            seed(directory, "stale")
            val rejectedBody = TrackedBody("")
            var requests = 0
            val downloader = DirectDownloader.forResponseTest { _, offset, state ->
                requests++
                if (requests == 1) {
                    assertEquals(5L, offset)
                    response(416, rejectedBody, offset, "bytes */5")
                } else {
                    assertTrue(rejectedBody.closed)
                    assertNull(offset)
                    assertNull(state)
                    throw IOException("fresh request failed")
                }
            }

            try {
                downloader.download(job(), directory) { }
                fail("Fresh request failure must propagate")
            } catch (expected: IOException) {
                assertEquals("fresh request failed", expected.message)
            }

            assertEquals(2, requests)
            assertFalse(File(directory, "payload.bin").exists())
            assertFalse(File(directory, "download.part").exists())
        }
    }

    @Test
    fun valid206AppendsAndFinalizesWithoutFreshRequest() = runBlocking {
        withStaging { directory ->
            seed(directory, "first")
            val body = TrackedBody("last")
            var requests = 0
            val downloader = DirectDownloader.forResponseTest { _, offset, state ->
                requests++
                assertEquals(5L, offset)
                assertEquals("\"old\"", state?.validator)
                response(206, body, offset, "bytes 5-8/9")
            }

            val result = downloader.download(job(), directory) { }

            assertEquals(1, requests)
            assertEquals("firstlast", result.file.readText())
            assertTrue(body.closed)
            assertFalse(File(directory, "download.part").exists())
            assertFalse(File(directory, "download.resume").exists())
        }
    }

    private suspend fun withStaging(block: suspend (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "direct-transfer-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun seed(directory: File, bytes: String) {
        File(directory, "download.part").writeText(bytes)
        File(directory, "download.resume").writeText("$URL\n\"old\"")
    }

    private fun response(code: Int, body: ResponseBody, offset: Long?, range: String? = null): Response {
        // Reflect the requested resume arguments in the synthetic response's request.
        // This seam tests transfer orchestration, not the production wire request builder.
        val request = Request.Builder().url(URL).apply {
            if (offset != null) header("Range", "bytes=$offset-")
        }.build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body)
            .header("Content-Disposition", "attachment; filename=payload.bin")
            .header("ETag", "\"old\"")
            .apply { if (range != null) header("Content-Range", range) }
            .build()
    }

    private class TrackedBody(text: String) : ResponseBody() {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        var closed = false
            private set
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = buffered
    }

    private fun job() = DownloadJob(
        id = "direct-transfer-test", sourceUrl = URL, sourceKind = SourceKind.DIRECT_FILE,
        format = DownloadFormat.ORIGINAL, title = "payload.bin", thumbnailUrl = null,
        status = JobStatus.RUNNING, progress = 0, bytesDownloaded = null, totalBytes = null,
        speedBytesPerSecond = null, etaSeconds = null, outputUri = null, fileName = null,
        mimeType = null, errorMessage = null, createdAt = 0L, updatedAt = 0L,
    )

    companion object {
        private const val URL = "https://example.com/payload.bin"
    }
}
