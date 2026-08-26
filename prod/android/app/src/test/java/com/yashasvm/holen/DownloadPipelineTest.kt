package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPipelineTest {
    @Test
    fun directTransferPartFileCannotBeOverwrittenByServerFilename() {
        assertEquals(
            "download-download.part",
            DirectDownloader.completionFileName("download.part"),
        )
        assertEquals("episode.mp4", DirectDownloader.completionFileName("episode.mp4"))
    }

    @Test
    fun interruptedDownloadsCanResumeButCompletedFilesCannotReenterQueue() {
        assertTrue(JobStatus.RUNNING.canTransitionTo(JobStatus.QUEUED))
        assertTrue(JobStatus.FINALIZING.canTransitionTo(JobStatus.QUEUED))
        assertFalse(JobStatus.COMPLETED.canTransitionTo(JobStatus.QUEUED))
    }

    @Test
    fun publicationRecoveryNeverTreatsUnknownOrPartialOutputAsComplete() {
        assertEquals(
            PublicationMatch.COMPLETE,
            publicationMatch("video.mp4", 100, "video.mp4", 100),
        )
        assertEquals(
            PublicationMatch.PARTIAL,
            publicationMatch("video.mp4", 100, "video.mp4", 99),
        )
        assertEquals(
            PublicationMatch.UNAVAILABLE,
            publicationMatch("video.mp4", 100, "video.mp4", null),
        )
    }
}
