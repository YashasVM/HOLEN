package com.yashasvm.holen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InterruptedDownloadStagingInstrumentedTest {
    @Test
    fun processRestartKeepsKnownJobResumeStateWhileRequeueingInterruptedWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HolenStore.get(context)
        val outputStore = OutputStore(context)
        val jobId = "resume-lifecycle-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val job = DownloadJob(
            id = jobId,
            sourceUrl = "https://example.com/video",
            sourceKind = SourceKind.MEDIA,
            format = DownloadFormat.BEST_MP4,
            title = "Resume lifecycle probe",
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
            createdAt = now,
            updatedAt = now,
        )

        store.remove(jobId)
        store.insert(listOf(job))
        val staging = outputStore.stagingDirectory(jobId)
        val partial = File(staging, "video.mp4.part").apply {
            writeBytes(ByteArray(64 * 1024) { index -> (index % 251).toByte() })
            setLastModified(1L)
        }
        val aria2State = File(staging, "video.mp4.part.aria2").apply {
            writeText("resume-state")
            setLastModified(1L)
        }
        staging.setLastModified(1L)

        try {
            outputStore.cleanOrphanStaging(now = now + 48L * 60 * 60 * 1000)

            assertTrue("Known interrupted job staging must survive startup cleanup", staging.isDirectory)
            assertTrue("Partial media must survive startup cleanup", partial.isFile)
            assertTrue("aria2 control state must survive startup cleanup", aria2State.isFile)

            store.requeueInterrupted()
            val recovered = requireNotNull(store.get(jobId))
            assertEquals(JobStatus.QUEUED, recovered.status)
            assertEquals("Interrupted. Resuming download.", recovered.errorMessage)

            assertTrue("Requeue must not delete partial media", partial.isFile)
            assertTrue("Requeue must not delete aria2 control state", aria2State.isFile)
        } finally {
            outputStore.clearStaging(jobId)
            store.remove(jobId)
        }
    }
}
