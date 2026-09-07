package com.yashasvm.holen

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class DownloadServiceRecoveryInstrumentedTest {
    @Test
    fun serviceStartupRequeuesInterruptedJobWithoutDiscardingResumeStaging() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HolenStore.get(context)
        val outputStore = OutputStore(context)
        val jobId = "service-resume-${UUID.randomUUID()}"
        val initialUpdatedAt = System.currentTimeMillis() - 60_000
        val server = ServerSocket(0)
        val releaseServer = CountDownLatch(1)
        val serverThread = thread(name = "holen-recovery-fixture", isDaemon = true) {
            try {
                server.accept().use {
                    releaseServer.await(20, TimeUnit.SECONDS)
                }
            } catch (_: Throwable) {
                // Test cleanup closes the listener to unblock accept/read.
            }
        }
        val serviceIntent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_WAKE_QUEUE)
        val job = DownloadJob(
            id = jobId,
            sourceUrl = "http://127.0.0.1:${server.localPort}/media.mp4",
            sourceKind = SourceKind.MEDIA,
            format = DownloadFormat.BEST_MP4,
            title = "Service recovery probe",
            thumbnailUrl = null,
            status = JobStatus.RUNNING,
            progress = 37,
            bytesDownloaded = 2L * 1024 * 1024,
            totalBytes = 6L * 1024 * 1024,
            speedBytesPerSecond = null,
            etaSeconds = null,
            outputUri = null,
            fileName = null,
            mimeType = null,
            errorMessage = null,
            createdAt = initialUpdatedAt - 1_000,
            updatedAt = initialUpdatedAt,
        )

        context.stopService(Intent(context, DownloadService::class.java))
        for (attempt in 0 until 40) {
            if (!DownloadService.isRunning) break
            delay(50)
        }

        store.remove(jobId)
        store.insert(listOf(job))
        val staging = outputStore.stagingDirectory(jobId)
        val partial = File(staging, "video.mp4.part").apply {
            writeBytes(ByteArray(64 * 1024) { index -> (index % 251).toByte() })
        }
        val aria2State = File(staging, "video.mp4.part.aria2").apply {
            writeText("resume-state")
        }

        try {
            context.startForegroundService(serviceIntent)

            var recovered: DownloadJob? = null
            for (attempt in 0 until 200) {
                val current = store.get(jobId)
                if (
                    current?.status == JobStatus.RUNNING &&
                    current.updatedAt > initialUpdatedAt
                ) {
                    recovered = current
                    break
                }
                delay(50)
            }

            val claimed = requireNotNull(recovered) {
                "DownloadService did not requeue and reclaim the interrupted job"
            }
            assertEquals(JobStatus.RUNNING, claimed.status)
            assertTrue("Service recovery must preserve partial media", partial.isFile)
            assertTrue("Service recovery must preserve aria2 control state", aria2State.isFile)

            // The service-level contract is that restart recovery reclaims the job
            // without deleting resumable staging. Actual yt-dlp/aria2 restart and
            // Range behaviour is covered by the dedicated resume instrumentation
            // probe, so this test deliberately does not depend on cold engine startup.
            delay(500)
            assertTrue("Partial media must remain after service reclaim", partial.isFile)
            assertTrue("aria2 state must remain after service reclaim", aria2State.isFile)
        } finally {
            context.stopService(Intent(context, DownloadService::class.java))
            releaseServer.countDown()
            runCatching { server.close() }
            serverThread.join(2_000)
            outputStore.clearStaging(jobId)
            store.remove(jobId)
        }
    }
}
